package net.earthcomputer.clientcommands.features;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import net.earthcomputer.clientcommands.ForgeHooks;
import net.earthcomputer.clientcommands.TempRules;
import net.earthcomputer.clientcommands.command.ClientCommandManager;
import net.earthcomputer.clientcommands.task.LongTaskList;
import net.earthcomputer.clientcommands.task.OneTickTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.container.EnchantingTableContainer;
import net.minecraft.container.Slot;
import net.minecraft.container.SlotActionType;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.enchantment.InfoEnchantment;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.packet.PlayerMoveC2SPacket;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.registry.Registry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.earthcomputer.clientcommands.task.LongTask;
import net.earthcomputer.clientcommands.task.TaskManager;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class EnchantmentCracker {

    /*
     * The enchantment cracker works as follows:
     *
     * First, crack the first few XP seeds. When you open an enchantment table GUI,
     * the server gives you 12 bits of the 32-bit enchantment seed. Vanilla uses
     * this masked version of the seed to generate the galactic alphabet text in the
     * GUI. We use brute force to guess the other 20 bits, matching each possibility
     * and what it would generate with certain things the server tells us, such as
     * the enchantment hints. We can narrow down the possibilities to 1 after
     * putting a few items into the enchantment table.
     *
     * Second, we know that the above XP seeds are generated by calling the player
     * entity's RNG's unbounded nextInt() method. This means that after a doing the
     * above a few times, enchanting an item after each time, we have a few
     * consecutive values of nextInt(). Each time an item is enchanted, we narrow
     * down the possibilities of what the player RNG's state could be. The first
     * value of nextInt() gives us 32 bits of its 48-bit internal state. Each time
     * nextInt() is next called, we narrow down its internal state by brute force.
     * It usually only takes two values of nextInt() to guess the internal state.
     *
     * There's one small catch: for this to work, we have to know that the values of
     * nextInt() are indeed consecutive. The first XP seed, if it's cracked, cannot
     * be used as one of these values since it was generated an unknown length of
     * time in the past, possibly even before a server restart - so we have to
     * ignore that. More obviously, there are many, many other things which use the
     * player's RNG and hence affect its internal state. We have to detect on the
     * client side when one of these things is likely to be happening. This is only
     * possible to do for certain if the server is running vanilla because some mod
     * could use the player's RNG for some miscellaneous task.
     *
     * Third, we can take advantage of the fact that generating XP seeds is not the
     * only thing that the player RNG does, to manipulate the RNG to produce an XP
     * seed which we want. The /cenchant command, which calls the
     * manipulateEnchantments method of this class, does this. We change the state
     * of the player RNG in a predictable way by throwing out items of the player's
     * inventory. Each time the player throws out an item, rand.nextFloat() gets
     * called 4 times to determine the velocity of the item which is thrown out. If
     * we throw out n items before we then do a dummy enchantment to generate our
     * new enchantment seed, then we can change n to change the enchantment seed. By
     * simulating which XP seed each n (up to a limit) will generate, and which
     * enchantments that XP seed will generate, we can filter out which enchantments
     * we want and determine n.
     */

    public static final Logger LOGGER = LogManager.getLogger("EnchantmentCracker");

    // RNG CHECK
    /*
     * The RNG check tries to detect client-side every single case where the
     * player's RNG could be called server side. The only known case (other than a
     * modded server) where this doesn't work is currently when a server operator
     * other than you gives you an item with the /give command. This is deemed
     * undetectable on the client-side.
     */

    private static int expectedThrows = 0;

    public static void resetCracker(String reason) {
        if (TempRules.enchCrackState != EnumCrackState.UNCRACKED) {
            ClientCommandManager.sendFeedback(new LiteralText(Formatting.RED + I18n.translate(
                    "enchCrack.reset", I18n.translate("enchCrack.reset." + reason))));
        }
        resetCracker();
    }

    public static void onDropItem() {
        if (expectedThrows > 0)
            expectedThrows--;
        else if (canMaintainPlayerRNG())
            for (int i = 0; i < 4; i++)
                playerRand.nextInt();
        else
            resetCracker("dropItem");
    }

    public static void onEntityCramming() {
        resetCracker("entityCramming");
    }

    public static void onDrink() {
        resetCracker("drink");
    }

    public static void onEat() {
        resetCracker("food");
    }

    public static void onUnderwater() {
        resetCracker("swim");
    }

    public static void onSwimmingStart() {
        resetCracker("enterWater");
    }

    public static void onDamage() {
        resetCracker("playerHurt");
    }

    public static void onSprinting() {
        resetCracker("sprint");
    }

    public static void onEquipmentBreak() {
        resetCracker("itemBreak");
    }

    public static void onPotionParticles() {
        resetCracker("potion");
    }

    public static void onGiveCommand() {
        resetCracker("give");
    }

    public static void onAnvilUse() {
        if (canMaintainPlayerRNG())
            playerRand.nextInt();
        else
            resetCracker("anvil");
    }

    public static void onFrostWalker() {
        resetCracker("frostWalker");
    }

    public static void onBaneOfArthropods() {
        if (canMaintainPlayerRNG())
            playerRand.nextInt();
        else
            resetCracker("baneOfArthropods");
    }

    public static void onRecreatePlayer() {
        resetCracker("recreated");
    }

    public static void onUnbreaking(ItemStack stack, int amount, int unbreakingLevel) {
        if (canMaintainPlayerRNG())
            for (int i = 0; i < amount; i++)
                if (!(stack.getItem() instanceof ArmorItem) || playerRand.nextFloat() >= 0.6)
                    playerRand.nextInt(unbreakingLevel + 1);
        else
            resetCracker("unbreaking");
    }

    public static void onUnbreakingUncertain(ItemStack stack, int minAmount, int maxAmount, int unbreakingLevel) {
        resetCracker("unbreaking");
    }

    public static void onItemDamage(int amount, LivingEntity holder, ItemStack stack) {
        if (holder instanceof ClientPlayerEntity && !((ClientPlayerEntity) holder).abilities.creativeMode) {
            if (stack.isDamageable()) {
                if (amount > 0) {
                    int unbreakingLevel = EnchantmentHelper.getLevel(Enchantments.UNBREAKING, stack);
                    if (unbreakingLevel > 0)
                        onUnbreaking(stack, amount, unbreakingLevel);

                    if (TempRules.toolBreakWarning && stack.getDamage() + amount >= stack.getMaxDamage() - 30) {
                        MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                                new TranslatableText("enchCrack.toolBreakWarning", stack.getMaxDamage() - stack.getDamage() - 1),
                                false);
                    }
                }
            }
        }
    }

    public static void onItemDamageUncertain(int minAmount, int maxAmount, LivingEntity holder, ItemStack stack) {
        if (holder instanceof ClientPlayerEntity && !((ClientPlayerEntity) holder).abilities.creativeMode) {
            if (stack.isDamageable()) {
                if (maxAmount > 0) {
                    int unbreakingLevel = EnchantmentHelper.getLevel(Enchantments.UNBREAKING, stack);
                    if (unbreakingLevel > 0)
                        onUnbreakingUncertain(stack, minAmount, maxAmount, unbreakingLevel);
                }
            }
        }
    }

    private static boolean canMaintainPlayerRNG() {
        return TempRules.playerRNGMaintenance && (TempRules.enchCrackState == EnumCrackState.CRACKED || TempRules.enchCrackState == EnumCrackState.CRACKED_PLAYER_SEED);
    }

    // RENDERING
    /*
     * This section is in charge of rendering the overlay on the enchantment GUI
     */

    public static void drawEnchantmentGUIOverlay() {
        EnumCrackState crackState = TempRules.enchCrackState;

        List<String> lines = new ArrayList<>();

        lines.add(I18n.translate("enchCrack.state", I18n.translate("enchCrack.state." + crackState.asString())));

        lines.add("");

        if (crackState == EnumCrackState.CRACKED_ENCH_SEED) {
            lines.add(I18n.translate("enchCrack.xpSeed.one", possibleXPSeeds.iterator().next()));
        } else if (crackState == EnumCrackState.CRACKING_ENCH_SEED) {
            lines.add(I18n.translate("enchCrack.xpSeed.many", possibleXPSeeds.size()));
        } else if (crackState == EnumCrackState.CRACKING && !possiblePlayerRandSeeds.isEmpty()) {
            lines.add(I18n.translate("enchCrack.playerRNGSeed.many", possiblePlayerRandSeeds.size()));
        }

        lines.add("");

        if (crackState == EnumCrackState.CRACKED || crackState == EnumCrackState.CRACKED_ENCH_SEED) {
            lines.add(I18n.translate("enchCrack.enchantments"));
        } else {
            lines.add(I18n.translate("enchCrack.clues"));
        }

        for (int slot = 0; slot < 3; slot++) {
            lines.add(I18n.translate("enchCrack.slot", slot + 1));
            List<InfoEnchantment> enchs = getEnchantmentsInTable(slot);
            if (enchs != null) {
                for (InfoEnchantment ench : enchs) {
                    lines.add("   " + ench.enchantment.getName(ench.level).getString());
                }
            }
        }

        TextRenderer fontRenderer = MinecraftClient.getInstance().textRenderer;
        int y = 0;
        for (String line : lines) {
            fontRenderer.draw(line, 0, y, 0xffffff);
            y += fontRenderer.fontHeight;
        }
    }

    // LOGIC
    /*
     * This section is in charge of the logic of the cracking
     */

    public static final long MULTIPLIER = 0x5deece66dL;
    public static final long ADDEND = 0xbL;
    public static final long MASK = (1L << 48) - 1;

    private static Set<Integer> possibleXPSeeds = new HashSet<>(1 << 20);
    private static boolean onFirstXPSeed = true;
    private static Set<Long> possiblePlayerRandSeeds = new HashSet<>(1 << 16);
    public static Random playerRand = new Random();
    private static boolean doneEnchantment = false;
    public static BlockPos enchantingTablePos = null;

    public static void resetCracker() {
        TempRules.enchCrackState = EnumCrackState.UNCRACKED;
        onFirstXPSeed = true;
        possibleXPSeeds.clear();
        possiblePlayerRandSeeds.clear();
    }

    private static void prepareForNextEnchantmentSeedCrack(int serverReportedXPSeed) {
        serverReportedXPSeed &= 0x0000fff0;
        for (int highBits = 0; highBits < 65536; highBits++) {
            for (int low4Bits = 0; low4Bits < 16; low4Bits++) {
                possibleXPSeeds.add((highBits << 16) | serverReportedXPSeed | low4Bits);
            }
        }
    }

    public static void addEnchantmentSeedInfo(World world, EnchantingTableContainer container) {
        EnumCrackState crackState = TempRules.enchCrackState;
        if (crackState == EnumCrackState.CRACKED_ENCH_SEED || crackState == EnumCrackState.CRACKED) {
            return;
        }

        ItemStack itemToEnchant = container.getSlot(0).getStack();
        if (itemToEnchant.isEmpty() || !itemToEnchant.isEnchantable()) {
            return;
        }

        if (enchantingTablePos == null)
            return;
        BlockPos tablePos = enchantingTablePos;

        if (crackState == EnumCrackState.UNCRACKED || crackState == EnumCrackState.CRACKING) {
            TempRules.enchCrackState = EnumCrackState.CRACKING_ENCH_SEED;
            prepareForNextEnchantmentSeedCrack(container.getSeed());
        }
        int power = getEnchantPower(world, tablePos);

        Random rand = new Random();
        int[] actualEnchantLevels = container.enchantmentPower;
        int[] actualEnchantmentClues = container.enchantmentId;
        int[] actualLevelClues = container.enchantmentLevel;

        // brute force the possible seeds
        Iterator<Integer> xpSeedItr = possibleXPSeeds.iterator();
        seedLoop: while (xpSeedItr.hasNext()) {
            int xpSeed = xpSeedItr.next();
            rand.setSeed(xpSeed);

            // check enchantment levels match
            for (int slot = 0; slot < 3; slot++) {
                int level = EnchantmentHelper.calculateEnchantmentPower(rand, slot, power, itemToEnchant);
                if (level < slot + 1) {
                    level = 0;
                }
                level = ForgeHooks.instance().ForgeEventFactory_onEnchantmentLevelSet(world, tablePos, slot, power, itemToEnchant, level);
                if (level != actualEnchantLevels[slot]) {
                    xpSeedItr.remove();
                    continue seedLoop;
                }
            }

            // generate enchantment clues and see if they match
            for (int slot = 0; slot < 3; slot++) {
                if (actualEnchantLevels[slot] > 0) {
                    List<InfoEnchantment> enchantments = getEnchantmentList(rand, xpSeed, itemToEnchant, slot,
                            actualEnchantLevels[slot]);
                    if (enchantments == null || enchantments.isEmpty()) {
                        // check that there is indeed no enchantment clue
                        if (actualEnchantmentClues[slot] != -1 || actualLevelClues[slot] != -1) {
                            xpSeedItr.remove();
                            continue seedLoop;
                        }
                    } else {
                        // check the right enchantment clue was generated
                        InfoEnchantment clue = enchantments.get(rand.nextInt(enchantments.size()));
                        if (Registry.ENCHANTMENT.getRawId(clue.enchantment) != actualEnchantmentClues[slot]
                                || clue.level != actualLevelClues[slot]) {
                            xpSeedItr.remove();
                            continue seedLoop;
                        }
                    }
                }
            }
        }

        // test the outcome, see if we need to change state
        if (possibleXPSeeds.size() == 0) {
            TempRules.enchCrackState = EnumCrackState.INVALID;
            LOGGER.warn(
                    "Invalid enchantment seed information. Has the server got unknown mods, is there a desync, or is the client just bugged?");
        } else if (possibleXPSeeds.size() == 1) {
            TempRules.enchCrackState = EnumCrackState.CRACKED_ENCH_SEED;
            if (!onFirstXPSeed) {
                addPlayerRNGInfo(possibleXPSeeds.iterator().next());
            }
            onFirstXPSeed = false;
        }
    }

    private static void addPlayerRNGInfo(int enchantmentSeed) {
        EnumCrackState crackState = TempRules.enchCrackState;
        if (crackState == EnumCrackState.CRACKED || crackState == EnumCrackState.CRACKED_PLAYER_SEED) {
            return;
        }

        long newSeedHigh = ((long) enchantmentSeed << 16) & 0x0000_ffff_ffff_0000L;
        if (possiblePlayerRandSeeds.isEmpty() && crackState != EnumCrackState.INVALID) {
            // add initial 2^16 possibilities
            for (int lowBits = 0; lowBits < 65536; lowBits++) {
                possiblePlayerRandSeeds.add(newSeedHigh | lowBits);
            }
        } else {
            // it's okay to allocate a new one, it will likely be small anyway
            Set<Long> newPlayerRandSeeds = new HashSet<>();
            // narrow down possibilities using brute force
            for (long oldSeed : possiblePlayerRandSeeds) {
                // this is what Random.nextInt() does internally
                long newSeed = (oldSeed * MULTIPLIER + ADDEND) & MASK;
                if ((newSeed & 0x0000_ffff_ffff_0000L) == newSeedHigh) {
                    newPlayerRandSeeds.add(newSeed);
                }
            }
            // add the new seed, not the old one, since the state of the RNG has changed
            // server-side
            possiblePlayerRandSeeds.clear();
            possiblePlayerRandSeeds.addAll(newPlayerRandSeeds);

            // check the outcome, see if we need to change state
            if (possiblePlayerRandSeeds.size() == 0) {
                TempRules.enchCrackState = EnumCrackState.INVALID;
                LOGGER.warn(
                        "Invalid player RNG information. Has the server got unknown mods, is there a desync, has an operator used /give, or is the client just bugged?");
            } else if (possiblePlayerRandSeeds.size() == 1) {
                TempRules.enchCrackState = EnumCrackState.CRACKED;
                playerRand.setSeed(possiblePlayerRandSeeds.iterator().next() ^ MULTIPLIER);
                possiblePlayerRandSeeds.clear();
            }
        }
    }

    public static void onEnchantedItem() {
        doneEnchantment = true;
        EnumCrackState crackState = TempRules.enchCrackState;
        if (crackState == EnumCrackState.CRACKED || crackState == EnumCrackState.CRACKED_PLAYER_SEED) {
            possibleXPSeeds.clear();
            possibleXPSeeds.add(playerRand.nextInt());
            TempRules.enchCrackState = EnumCrackState.CRACKED;
        } else if (crackState == EnumCrackState.CRACKED_ENCH_SEED) {
            possibleXPSeeds.clear();
            TempRules.enchCrackState = EnumCrackState.CRACKING;
        } else {
            resetCracker();
            onFirstXPSeed = false;
        }
    }

    // ENCHANTMENT MANIPULATION
    /*
     * This section is involved in actually manipulating the enchantments and the XP
     * seed
     */

    private static EnchantManipulationStatus manipulateEnchantmentsSanityCheck(PlayerEntity player) {
        if (TempRules.enchCrackState != EnumCrackState.CRACKED && TempRules.enchCrackState != EnumCrackState.CRACKED_PLAYER_SEED) {
            return EnchantManipulationStatus.NOT_CRACKED;
        } else if (!player.onGround) {
            return EnchantManipulationStatus.NOT_ON_GROUND;
        } else if (player.container.getStacks().stream().allMatch(ItemStack::isEmpty)) {
            return EnchantManipulationStatus.EMPTY_INVENTORY;
        } else {
            return EnchantManipulationStatus.OK;
        }
    }

    public static EnchantManipulationStatus manipulateEnchantments(Item item,
                                                                   Predicate<List<InfoEnchantment>> enchantmentsPredicate) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;

        EnchantManipulationStatus status = manipulateEnchantmentsSanityCheck(player);
        if (status != EnchantManipulationStatus.OK) {
            return status;
        }

        ItemStack stack = new ItemStack(item);
        long seed = getSeed(playerRand);
        // -2: not found; -1: no dummy enchantment needed; >= 0: number of times needed
        // to throw out item before dummy enchantment
        int timesNeeded = -2;
        int bookshelvesNeeded = 0;
        int slot = 0;
        int[] enchantLevels = new int[3];
        outerLoop: for (int i = TempRules.enchCrackState == EnumCrackState.CRACKED_PLAYER_SEED ? 0 : -1; i < 1000; i++) {
            int xpSeed = (int) ((i == -1 ? seed : ((seed * MULTIPLIER + ADDEND) & MASK)) >>> 16);
            Random rand = new Random();
            for (bookshelvesNeeded = 0; bookshelvesNeeded <= 15; bookshelvesNeeded++) {
                rand.setSeed(xpSeed);
                for (slot = 0; slot < 3; slot++) {
                    int level = EnchantmentHelper.calculateEnchantmentPower(rand, slot, bookshelvesNeeded, stack);
                    if (level < slot + 1) {
                        level = 0;
                    }
                    enchantLevels[slot] = level;
                }
                for (slot = 0; slot < 3; slot++) {
                    List<InfoEnchantment> enchantments = getEnchantmentList(rand, xpSeed, stack, slot,
                            enchantLevels[slot]);
                    if (enchantmentsPredicate.test(enchantments)) {
                        timesNeeded = i;
                        break outerLoop;
                    }
                }
            }

            if (i != -1) {
                for (int j = 0; j < 4; j++) {
                    seed = (seed * MULTIPLIER + ADDEND) & MASK;
                }
            }
        }
        if (timesNeeded == -2) {
            return EnchantManipulationStatus.IMPOSSIBLE;
        }

        LongTaskList taskList = new LongTaskList();
        if (timesNeeded != -1) {
            if (timesNeeded != 0) {
                player.setPositionAndAngles(player.x, player.y, player.z, player.yaw, 90);
                // sync rotation to server before we throw any items
                player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookOnly(player.yaw, 90, player.onGround));
            }
            for (int i = 0; i < timesNeeded; i++) {
                // throw the item once it's in the inventory
                taskList.addTask(new LongTask() {
                    @Override
                    public void initialize() {
                    }

                    @Override
                    public boolean condition() {
                        EnchantManipulationStatus status = manipulateEnchantmentsSanityCheck(player);
                        if (status == EnchantManipulationStatus.OK)
                            return false; // ready to throw an item
                        if (status == EnchantManipulationStatus.EMPTY_INVENTORY)
                            return true; // keep waiting
                        player.sendMessage(new LiteralText(Formatting.RED + I18n.translate(status.getTranslation())));
                        taskList._break();
                        return false;
                    }

                    @Override
                    public void increment() {
                    }

                    @Override
                    public void body() {
                        scheduleDelay();
                    }

                    @Override
                    public void onCompleted() {
                        EnchantManipulationStatus status = throwItem();
                        assert status == EnchantManipulationStatus.OK;

                        scheduleDelay();
                    }
                });
            }
            // dummy enchantment
            taskList.addTask(new LongTask() {
                @Override
                public void initialize() {
                    player.sendMessage(new TranslatableText("enchCrack.insn.dummy"));
                    doneEnchantment = false;
                }

                @Override
                public boolean condition() {
                    return !doneEnchantment;
                }

                @Override
                public void increment() {
                }

                @Override
                public void body() {
                    scheduleDelay();
                }
            });
        }
        final int bookshelvesNeeded_f = bookshelvesNeeded;
        final int slot_f = slot;
        taskList.addTask(new OneTickTask() {
            @Override
            public void run() {
                player.sendMessage(new LiteralText(Formatting.BOLD + I18n.translate("enchCrack.insn.ready")));
                player.sendMessage(new TranslatableText("enchCrack.insn.bookshelves", bookshelvesNeeded_f));
                player.sendMessage(new TranslatableText("enchCrack.insn.slot", slot_f + 1));
            }
        });

        TaskManager.addTask("enchantmentCracker", taskList);

        return EnchantManipulationStatus.OK;
    }

    /*
    public static EnchantManipulationStatus throwItemsUntil(Predicate<Random> condition) {
        return throwItemsUntil(condition, Integer.MAX_VALUE);
    }
    */

    public static EnchantManipulationStatus throwItemsUntil(Predicate<Random> condition, int max) {
        if (TempRules.enchCrackState != EnumCrackState.CRACKED)
            return EnchantManipulationStatus.NOT_CRACKED;

        long seed = getSeed(playerRand);
        Random rand = new Random(seed ^ MULTIPLIER);

        int itemsNeeded = 0;
        for (; itemsNeeded <= max && !condition.test(rand); itemsNeeded++) {
            for (int i = 0; i < 4; i++)
                seed = (seed * MULTIPLIER + ADDEND) & MASK;
            rand.setSeed(seed ^ MULTIPLIER);
        }
        if (itemsNeeded > max)
            return EnchantManipulationStatus.IMPOSSIBLE;

        for (int i = 0; i < itemsNeeded; i++) {
            EnchantManipulationStatus status = throwItem();
            if (status != EnchantManipulationStatus.OK)
                return status;
        }

        return EnchantManipulationStatus.OK;
    }

    public static EnchantManipulationStatus throwItem() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;

        EnchantManipulationStatus status = manipulateEnchantmentsSanityCheck(player);
        if (status != EnchantManipulationStatus.OK && status != EnchantManipulationStatus.NOT_CRACKED)
            return status;
        Slot matchingSlot = player.container.slotList.stream()
                .filter(Slot::hasStack).findAny().orElse(null);
        if (matchingSlot == null) {
            return EnchantManipulationStatus.EMPTY_INVENTORY;
        }
        if (status != EnchantManipulationStatus.NOT_CRACKED) {
            expectedThrows++;
            for (int j = 0; j < 4; j++) {
                playerRand.nextInt();
            }
        }
        MinecraftClient.getInstance().interactionManager.method_2906(player.container.syncId,
                matchingSlot.id, 0, SlotActionType.THROW, player);

        return status;
    }

    public static long singlePlayerCrackRNG() {
        ServerPlayerEntity serverPlayer = MinecraftClient.getInstance().getServer().getPlayerManager().getPlayer(MinecraftClient.getInstance().player.getUuid());
        long seed = getSeed(serverPlayer.getRand());
        playerRand.setSeed(seed ^ MULTIPLIER);

        possibleXPSeeds.clear();
        possibleXPSeeds.add(serverPlayer.getEnchantmentTableSeed());

        TempRules.enchCrackState = EnumCrackState.CRACKED;
        return seed;
    }

    public static enum EnchantManipulationStatus {
        // @formatter:off
        OK("ok"),
        NOT_CRACKED("notCracked"),
        NOT_ON_GROUND("notOnGround"),
        EMPTY_INVENTORY("emptyInventory"),
        IMPOSSIBLE("impossible");
        // @formatter:on

        private String translation;

        private EnchantManipulationStatus(String translation) {
            this.translation = translation;
        }

        public String getTranslation() {
            return "enchCrack.manipStatus." + translation;
        }
    }

    // MISCELLANEOUS HELPER METHODS & ENCHANTING SIMULATION

    public static boolean isEnchantingPredictionEnabled() {
        return TempRules.getEnchantingPrediction();
    }

    private static int getEnchantPower(World world, BlockPos tablePos) {
        float power = 0;

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dz != 0 || dx != 0) && world.isAir(tablePos.add(dx, 0, dz))
                        && world.isAir(tablePos.add(dx, 1, dz))) {
                    power += ForgeHooks.instance().ForgeHooks_getEnchantPower(world, tablePos.add(dx * 2, 0, dz * 2));
                    power += ForgeHooks.instance().ForgeHooks_getEnchantPower(world, tablePos.add(dx * 2, 1, dz * 2));
                    if (dx != 0 && dz != 0) {
                        power += ForgeHooks.instance().ForgeHooks_getEnchantPower(world, tablePos.add(dx * 2, 0, dz));
                        power += ForgeHooks.instance().ForgeHooks_getEnchantPower(world, tablePos.add(dx * 2, 1, dz));
                        power += ForgeHooks.instance().ForgeHooks_getEnchantPower(world, tablePos.add(dx, 0, dz * 2));
                        power += ForgeHooks.instance().ForgeHooks_getEnchantPower(world, tablePos.add(dx, 1, dz * 2));
                    }
                }
            }
        }

        return (int) power;
    }

    private static List<InfoEnchantment> getEnchantmentList(Random rand, int xpSeed, ItemStack stack, int enchantSlot,
                                                            int level) {
        rand.setSeed(xpSeed + enchantSlot);
        List<InfoEnchantment> list = EnchantmentHelper.getEnchantments(rand, stack, level, false);

        if (stack.getItem() == Items.BOOK && list.size() > 1) {
            list.remove(rand.nextInt(list.size()));
        }

        return list;
    }

    // Same as above method, except does not assume the seed has been cracked. If it
    // hasn't returns the clue given by the server
    public static List<InfoEnchantment> getEnchantmentsInTable(int slot) {
        EnumCrackState crackState = TempRules.enchCrackState;
        EnchantingTableContainer enchContainer = (EnchantingTableContainer) MinecraftClient.getInstance().player.container;

        if (crackState != EnumCrackState.CRACKED_ENCH_SEED && crackState != EnumCrackState.CRACKED) {
            if (enchContainer.enchantmentId[slot] == -1) {
                // if we haven't cracked it, and there's no clue, then we can't give any
                // information about the enchantment
                return null;
            } else {
                // return a list containing the clue
                return Collections.singletonList(
                        new InfoEnchantment(Enchantment.byRawId(enchContainer.enchantmentId[slot]),
                                enchContainer.enchantmentLevel[slot]));
            }
        } else {
            // return the enchantments using our cracked seed
            Random rand = new Random();
            int xpSeed = possibleXPSeeds.iterator().next();
            ItemStack enchantingStack = enchContainer.getSlot(0).getStack();
            int enchantLevels = enchContainer.enchantmentPower[slot];
            return getEnchantmentList(rand, xpSeed, enchantingStack, slot, enchantLevels);
        }
    }

    private static final Field RANDOM_SEED;
    static {
        try {
            RANDOM_SEED = Random.class.getDeclaredField("seed");
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
        RANDOM_SEED.setAccessible(true);
    }
    public static long getSeed(Random rand) {
        try {
            return ((AtomicLong) RANDOM_SEED.get(rand)).get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public static enum EnumCrackState implements StringIdentifiable {
        UNCRACKED("uncracked"), CRACKING_ENCH_SEED("crackingEnchSeed"), CRACKED_ENCH_SEED("crackedEnchSeed"), CRACKING(
                "cracking"), CRACKED("cracked"), CRACKED_PLAYER_SEED("crackedPlayerSeed"), INVALID("invalid");

        private String name;

        private EnumCrackState(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

}
