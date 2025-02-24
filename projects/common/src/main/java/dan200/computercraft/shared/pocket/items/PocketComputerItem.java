// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL

package dan200.computercraft.shared.pocket.items;

import dan200.computercraft.annotations.ForgeOverride;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.media.IMedia;
import dan200.computercraft.api.pocket.IPocketUpgrade;
import dan200.computercraft.api.upgrades.UpgradeData;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.impl.PocketUpgrades;
import dan200.computercraft.shared.common.IColouredItem;
import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.ServerContext;
import dan200.computercraft.shared.computer.items.IComputerItem;
import dan200.computercraft.shared.config.Config;
import dan200.computercraft.shared.network.container.ComputerContainerData;
import dan200.computercraft.shared.pocket.apis.PocketAPI;
import dan200.computercraft.shared.pocket.core.PocketServerComputer;
import dan200.computercraft.shared.pocket.inventory.PocketComputerMenuProvider;
import dan200.computercraft.shared.util.IDAssigner;
import dan200.computercraft.shared.util.NBTUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class PocketComputerItem extends Item implements IComputerItem, IMedia, IColouredItem {
    private static final String NBT_UPGRADE = "Upgrade";
    private static final String NBT_UPGRADE_INFO = "UpgradeInfo";
    public static final String NBT_ON = "On";

    private static final String NBT_INSTANCE = "InstanceId";
    private static final String NBT_SESSION = "SessionId";

    private final ComputerFamily family;

    public PocketComputerItem(Properties settings, ComputerFamily family) {
        super(settings);
        this.family = family;
    }

    public ItemStack create(int id, @Nullable String label, int colour, @Nullable UpgradeData<IPocketUpgrade> upgrade) {
        var result = new ItemStack(this);
        if (id >= 0) result.getOrCreateTag().putInt(NBT_ID, id);
        if (label != null) result.setHoverName(Component.literal(label));
        if (upgrade != null) {
            result.getOrCreateTag().putString(NBT_UPGRADE, upgrade.upgrade().getUpgradeID().toString());
            if (!upgrade.data().isEmpty()) result.getOrCreateTag().put(NBT_UPGRADE_INFO, upgrade.data().copy());
        }
        if (colour != -1) result.getOrCreateTag().putInt(NBT_COLOUR, colour);
        return result;
    }

    private boolean tick(ItemStack stack, Entity entity, PocketServerComputer computer) {
        var upgrade = getUpgrade(stack);

        computer.updateValues(entity, stack, upgrade);

        var changed = false;

        // Sync ID
        var id = computer.getID();
        if (id != getComputerID(stack)) {
            changed = true;
            setComputerID(stack, id);
        }

        // Sync label
        var label = computer.getLabel();
        if (!Objects.equals(label, getLabel(stack))) {
            changed = true;
            setLabel(stack, label);
        }

        var on = computer.isOn();
        if (on != isMarkedOn(stack)) {
            changed = true;
            stack.getOrCreateTag().putBoolean(NBT_ON, on);
        }

        // Update pocket upgrade
        if (upgrade != null) upgrade.update(computer, computer.getPeripheral(ComputerSide.BACK));

        return changed;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level world, Entity entity, int slotNum, boolean selected) {
        if (world.isClientSide) return;
        Container inventory = entity instanceof Player player ? player.getInventory() : null;
        var computer = createServerComputer((ServerLevel) world, entity, inventory, stack);
        computer.keepAlive();

        var changed = tick(stack, entity, computer);
        if (changed && inventory != null) inventory.setChanged();
    }

    @ForgeOverride
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        var level = entity.level();
        if (level.isClientSide || level.getServer() == null) return false;

        var computer = getServerComputer(level.getServer(), stack);
        if (computer != null && tick(stack, entity, computer)) entity.setItem(stack.copy());
        return false;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!world.isClientSide) {
            var computer = createServerComputer((ServerLevel) world, player, player.getInventory(), stack);
            computer.turnOn();

            var stop = false;
            var upgrade = getUpgrade(stack);
            if (upgrade != null) {
                computer.updateValues(player, stack, upgrade);
                stop = upgrade.onRightClick(world, computer, computer.getPeripheral(ComputerSide.BACK));
            }

            if (!stop) {
                var isTypingOnly = hand == InteractionHand.OFF_HAND;
                new ComputerContainerData(computer, stack).open(player, new PocketComputerMenuProvider(computer, stack, this, hand, isTypingOnly));
            }
        }
        return new InteractionResultHolder<>(InteractionResult.sidedSuccess(world.isClientSide), stack);
    }

    @Override
    public Component getName(ItemStack stack) {
        var baseString = getDescriptionId(stack);
        var upgrade = getUpgrade(stack);
        if (upgrade != null) {
            return Component.translatable(baseString + ".upgraded",
                Component.translatable(upgrade.getUnlocalisedAdjective())
            );
        } else {
            return super.getName(stack);
        }
    }


    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level world, List<Component> list, TooltipFlag flag) {
        if (flag.isAdvanced() || getLabel(stack) == null) {
            var id = getComputerID(stack);
            if (id >= 0) {
                list.add(Component.translatable("gui.computercraft.tooltip.computer_id", id)
                    .withStyle(ChatFormatting.GRAY));
            }
        }
    }

    @Nullable
    @ForgeOverride
    public String getCreatorModId(ItemStack stack) {
        var upgrade = getUpgrade(stack);
        if (upgrade != null) {
            // If we're a non-vanilla, non-CC upgrade then return whichever mod this upgrade
            // belongs to.
            var mod = PocketUpgrades.instance().getOwner(upgrade);
            if (mod != null && !mod.equals(ComputerCraftAPI.MOD_ID)) return mod;
        }

        return ComputerCraftAPI.MOD_ID;
    }

    public PocketServerComputer createServerComputer(ServerLevel level, Entity entity, @Nullable Container inventory, ItemStack stack) {

        var registry = ServerContext.get(level.getServer()).registry();
        var computer = (PocketServerComputer) registry.get(getSessionID(stack), getInstanceID(stack));
        if (computer == null) {
            var computerID = getComputerID(stack);
            if (computerID < 0) {
                computerID = ComputerCraftAPI.createUniqueNumberedSaveDir(level.getServer(), IDAssigner.COMPUTER);
                setComputerID(stack, computerID);
            }

            computer = new PocketServerComputer(level, entity.blockPosition(), getComputerID(stack), getLabel(stack), getFamily());

            var tag = stack.getOrCreateTag();
            tag.putInt(NBT_SESSION, registry.getSessionID());
            tag.putUUID(NBT_INSTANCE, computer.register());

            var upgrade = getUpgrade(stack);

            computer.updateValues(entity, stack, upgrade);
            computer.addAPI(new PocketAPI(computer));

            // Only turn on when initially creating the computer, rather than each tick.
            if (isMarkedOn(stack) && entity instanceof Player) computer.turnOn();

            if (inventory != null) inventory.setChanged();
        }

        return computer;
    }

    @Nullable
    public static PocketServerComputer getServerComputer(MinecraftServer server, ItemStack stack) {
        return (PocketServerComputer) ServerContext.get(server).registry().get(getSessionID(stack), getInstanceID(stack));
    }

    // IComputerItem implementation

    private static void setComputerID(ItemStack stack, int computerID) {
        stack.getOrCreateTag().putInt(NBT_ID, computerID);
    }

    @Override
    public @Nullable String getLabel(ItemStack stack) {
        return IComputerItem.super.getLabel(stack);
    }

    public ComputerFamily getFamily() {
        return family;
    }

    @Override
    public ItemStack changeItem(ItemStack stack, Item newItem) {
        return newItem instanceof PocketComputerItem pocket ? pocket.create(
            getComputerID(stack), getLabel(stack), getColour(stack),
            getUpgradeWithData(stack)
        ) : ItemStack.EMPTY;
    }

    // IMedia

    @Override
    public boolean setLabel(ItemStack stack, @Nullable String label) {
        if (label != null) {
            stack.setHoverName(Component.literal(label));
        } else {
            stack.resetHoverName();
        }
        return true;
    }

    @Override
    public @Nullable Mount createDataMount(ItemStack stack, ServerLevel level) {
        var id = getComputerID(stack);
        if (id >= 0) {
            return ComputerCraftAPI.createSaveDirMount(level.getServer(), "computer/" + id, Config.computerSpaceLimit);
        }
        return null;
    }

    public static @Nullable UUID getInstanceID(ItemStack stack) {
        var nbt = stack.getTag();
        return nbt != null && nbt.hasUUID(NBT_INSTANCE) ? nbt.getUUID(NBT_INSTANCE) : null;
    }

    private static int getSessionID(ItemStack stack) {
        var nbt = stack.getTag();
        return nbt != null && nbt.contains(NBT_SESSION) ? nbt.getInt(NBT_SESSION) : -1;
    }

    private static boolean isMarkedOn(ItemStack stack) {
        var nbt = stack.getTag();
        return nbt != null && nbt.getBoolean(NBT_ON);
    }

    public static @Nullable IPocketUpgrade getUpgrade(ItemStack stack) {
        var compound = stack.getTag();
        if (compound == null || !compound.contains(NBT_UPGRADE)) return null;
        return PocketUpgrades.instance().get(compound.getString(NBT_UPGRADE));
    }

    public static @Nullable UpgradeData<IPocketUpgrade> getUpgradeWithData(ItemStack stack) {
        var compound = stack.getTag();
        if (compound == null || !compound.contains(NBT_UPGRADE)) return null;
        var upgrade = PocketUpgrades.instance().get(compound.getString(NBT_UPGRADE));
        return upgrade == null ? null : UpgradeData.of(upgrade, NBTUtil.getCompoundOrEmpty(compound, NBT_UPGRADE_INFO));
    }

    public static void setUpgrade(ItemStack stack, @Nullable UpgradeData<IPocketUpgrade> upgrade) {
        var compound = stack.getOrCreateTag();

        if (upgrade == null) {
            compound.remove(NBT_UPGRADE);
            compound.remove(NBT_UPGRADE_INFO);
        } else {
            compound.putString(NBT_UPGRADE, upgrade.upgrade().getUpgradeID().toString());
            compound.put(NBT_UPGRADE_INFO, upgrade.data().copy());
        }
    }

    public static CompoundTag getUpgradeInfo(ItemStack stack) {
        return stack.getOrCreateTagElement(NBT_UPGRADE_INFO);
    }
}
