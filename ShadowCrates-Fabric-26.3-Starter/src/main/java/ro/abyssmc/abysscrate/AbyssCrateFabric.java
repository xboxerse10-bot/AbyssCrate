package ro.abyssmc.abysscrate;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Etapa 3A: administrare crate-uri + mesaje configurabile.
 * Nu contine inca interactiuni cu blocuri, chei sau recompense.
 */
public final class AbyssCrateFabric implements ModInitializer {
    private static final Path DIR = Path.of("config", "shadowcrates");
    private static final Path DATA = DIR.resolve("crates.properties");
    private static final Path MESSAGES_FILE = DIR.resolve("messages.properties");
    private static final Properties CRATES = new Properties();
    private static final Properties MESSAGES = new Properties();
    private static final Properties PLACEMENTS = new Properties();
    private static final Path PLACEMENTS_FILE = DIR.resolve("placements.properties");
    private static final Path REWARDS_FILE = DIR.resolve("rewards.properties");
    private static final Properties REWARDS = new Properties();
    private static final String KEY_TAG = "shadowcrates_key_type";

    @Override
    public void onInitialize() {
        try {
            loadCrates();
            loadMessages();
            loadPlacements();
            loadRewards();
        } catch (IOException ex) {
            throw new IllegalStateException("AbyssCrate: configuratia nu a putut fi incarcata", ex);
        }

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            String name;
            synchronized (PLACEMENTS) {
                name = PLACEMENTS.getProperty(locationKey(world.dimension().identifier().toString(), hit.getBlockPos()));
            }
            if (name == null) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
            ItemStack held = player.getItemInHand(hand);
            if (!isKeyFor(held, name)) {
                player.sendSystemMessage(Component.literal(message("needs-key", name)));
                return InteractionResult.SUCCESS;
            }
            RewardChoice selected = chooseReward(name);
            if (selected == null) {
                player.sendSystemMessage(Component.literal(message("no-rewards", name)));
                return InteractionResult.SUCCESS;
            }
            // For safety, require an empty inventory slot before awarding an item.
            // This prevents accidental item loss and avoids consuming a key on failure.
            if (!hasEmptyInventorySlot(serverPlayer)) {
                player.sendSystemMessage(Component.literal(message("inventory-full", name)));
                return InteractionResult.SUCCESS;
            }
            ItemStack prize = new ItemStack(selected.item(), selected.count());
            // Inventory.add can empty/mutate the input stack; capture the display name first.
            String rewardDisplayName = prize.getHoverName().getString();
            if (!serverPlayer.getInventory().add(prize)) {
                player.sendSystemMessage(Component.literal(message("inventory-full", name)));
                return InteractionResult.SUCCESS;
            }
            held.shrink(1);
            player.sendSystemMessage(Component.literal(message("reward-won", name)
                .replace("{item}", rewardDisplayName)
                .replace("{amount}", Integer.toString(selected.count()))));
            return InteractionResult.SUCCESS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            synchronized (PLACEMENTS) {
                if (!PLACEMENTS.containsKey(locationKey(world.dimension().identifier().toString(), pos))) return true;
            }
            if (!world.isClientSide()) player.sendSystemMessage(Component.literal(message("protected", "")));
            return false;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("crate")
                .then(Commands.literal("editor")
                    .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            synchronized (CRATES) {
                                if (!CRATES.containsKey(name)) {
                                    reply(ctx.getSource(), "not-found", name);
                                    return 0;
                                }
                            }
                            ServerPlayer admin = ctx.getSource().getPlayerOrException();
                            openMainEditor(admin, name);
                            return 1;
                        })))
            );
            dispatcher.register(Commands.literal("crate")
                .then(Commands.literal("givekey")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("name", StringArgumentType.word())
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                    synchronized (CRATES) {
                                        if (!CRATES.containsKey(name)) {
                                            reply(ctx.getSource(), "not-found", name);
                                            return 0;
                                        }
                                    }
                                    ItemStack key = createKey(name, amount);
                                    // Daca inventarul e plin, nu pierdem cheia: comanda esueaza.
                                    if (!target.getInventory().add(key)) {
                                        reply(ctx.getSource(), "inventory-full", name);
                                        return 0;
                                    }
                                    reply(ctx.getSource(), "key-given", name);
                                    return 1;
                                })))))
                .then(Commands.literal("set")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            synchronized (CRATES) {
                                if (!CRATES.containsKey(name)) {
                                    reply(ctx.getSource(), "not-found", name);
                                    return 0;
                                }
                            }
                            var player = ctx.getSource().getPlayerOrException();
                            HitResult target = player.pick(5.0, 0.0f, false);
                            if (!(target instanceof BlockHitResult hit) || target.getType() != HitResult.Type.BLOCK) {
                                reply(ctx.getSource(), "look-at-block", name);
                                return 0;
                            }
                            String key = locationKey(player.level().dimension().identifier().toString(), hit.getBlockPos());
                            synchronized (PLACEMENTS) {
                                String previous = (String) PLACEMENTS.setProperty(key, name);
                                try { savePlacements(); }
                                catch (IOException ex) {
                                    if (previous == null) PLACEMENTS.remove(key);
                                    else PLACEMENTS.setProperty(key, previous);
                                    reply(ctx.getSource(), "save-error", name);
                                    return 0;
                                }
                            }
                            reply(ctx.getSource(), "block-set", name);
                            return 1;
                        })))
                .then(Commands.literal("unset").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    HitResult target = player.pick(5.0, 0.0f, false);
                    if (!(target instanceof BlockHitResult hit) || target.getType() != HitResult.Type.BLOCK) {
                        reply(ctx.getSource(), "look-at-block", "");
                        return 0;
                    }
                    String key = locationKey(player.level().dimension().identifier().toString(), hit.getBlockPos());
                    synchronized (PLACEMENTS) {
                        String previous = (String) PLACEMENTS.remove(key);
                        if (previous == null) {
                            reply(ctx.getSource(), "not-a-crate", "");
                            return 0;
                        }
                        try { savePlacements(); }
                        catch (IOException ex) {
                            PLACEMENTS.setProperty(key, previous);
                            reply(ctx.getSource(), "save-error", previous);
                            return 0;
                        }
                        reply(ctx.getSource(), "block-unset", previous);
                    }
                    return 1;
                }))
                .then(Commands.literal("create")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            if (!valid(name)) {
                                reply(ctx.getSource(), "invalid-name", name);
                                return 0;
                            }
                            synchronized (CRATES) {
                                if (CRATES.containsKey(name)) {
                                    reply(ctx.getSource(), "already-exists", name);
                                    return 0;
                                }
                                CRATES.setProperty(name, "created");
                                try {
                                    saveCrates();
                                } catch (IOException ex) {
                                    CRATES.remove(name);
                                    reply(ctx.getSource(), "save-error", name);
                                    return 0;
                                }
                            }
                            reply(ctx.getSource(), "created", name);
                            return 1;
                        })))
                .then(Commands.literal("remove")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            synchronized (CRATES) {
                                String previous = (String) CRATES.remove(name);
                                if (previous == null) {
                                    reply(ctx.getSource(), "not-found", name);
                                    return 0;
                                }
                                try {
                                    saveCrates();
                                } catch (IOException ex) {
                                    CRATES.setProperty(name, previous);
                                    reply(ctx.getSource(), "save-error", name);
                                    return 0;
                                }
                            }
                            reply(ctx.getSource(), "removed", name);
                            return 1;
                        })))
                .then(Commands.literal("reload").executes(ctx -> {
                    try {
                        loadMessages();
                        loadCrates();
                        loadPlacements();
                        loadRewards();
                        reply(ctx.getSource(), "reloaded", "");
                        return 1;
                    } catch (IOException ex) {
                        reply(ctx.getSource(), "load-error", "");
                        return 0;
                    }
                }))
                .then(Commands.literal("list").executes(ctx -> {
                    Set<String> names;
                    synchronized (CRATES) {
                        names = new TreeSet<>(CRATES.stringPropertyNames());
                    }
                    String message = names.isEmpty()
                        ? message("empty", "")
                        : message("list", String.join(", ", names));
                    ctx.getSource().sendSuccess(() -> Component.literal(message), false);
                    return 1;
                })));
        });
    }

    // 3F: all editor menus are server-side and block moving the display buttons.
    private static ItemStack button(Item item, String label) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        return stack;
    }

    private static void openMainEditor(ServerPlayer admin, String crate) {
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(10, button(Items.EMERALD, "Add Item"));
        view.setItem(12, button(Items.CHEST, "View Rewards"));
        view.setItem(14, button(Items.COMPARATOR, "Change Chance - select reward"));
        view.setItem(16, button(Items.PAPER, "Crate: " + crate));
        view.setItem(22, button(Items.BARRIER, "Close"));
        admin.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput ContainerInput, Player player) {
                if (slot >= 0 && slot < 27) {
                    if (slot == 10) openAddEditor(admin, crate);
                    else if (slot == 12 || slot == 14) openRewardsEditor(admin, crate, 0);
                    else if (slot == 22) admin.closeContainer();
                    return;
                }
                super.clicked(slot, button, ContainerInput, player);
            }
        }, Component.literal("AbyssCrate | " + crate)));
    }

    private static void openAddEditor(ServerPlayer admin, String crate) {
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(4, button(Items.PAPER, "Pune recompensa in slotul 13, apoi inchide"));
        view.setItem(22, button(Items.BARRIER, "Inchide pentru a salva"));
        admin.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput ContainerInput, Player player) {
                if (slot >= 0 && slot < 27 && slot != 13) return;
                super.clicked(slot, button, ContainerInput, player);
            }
            @Override public void removed(Player player) {
                ItemStack reward = view.removeItemNoUpdate(13);
                if (!reward.isEmpty()) {
                    String itemId = BuiltInRegistries.ITEM.getKey(reward.getItem()).toString();
                    try {
                        synchronized (REWARDS) {
                            int next = 1;
                            while (REWARDS.containsKey(crate + "." + next)) next++;
                            REWARDS.setProperty(crate + "." + next, itemId + "|" + reward.getCount() + "|10");
                            saveRewards();
                        }
                        player.sendSystemMessage(Component.literal("[AbyssCrate] Reward saved: " + itemId));
                    } catch (IOException ex) {
                        player.sendSystemMessage(Component.literal("[AbyssCrate] Could not save reward."));
                    }
                    // Try to return the real item; do not duplicate it on a failed insert.
                    player.getInventory().add(reward);
                }
                super.removed(player);
            }
        }, Component.literal("Add Reward | " + crate)));
    }

    private static List<Integer> rewardIds(String crate) {
        List<Integer> ids = new ArrayList<>();
        synchronized (REWARDS) {
            for (String key : REWARDS.stringPropertyNames()) {
                if (!key.startsWith(crate + ".")) continue;
                try { ids.add(Integer.parseInt(key.substring(crate.length() + 1))); }
                catch (NumberFormatException ignored) { }
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    private static ItemStack rewardIcon(String crate, int id) {
        String value;
        synchronized (REWARDS) { value = REWARDS.getProperty(crate + "." + id, ""); }
        String[] parts = value.split("\\|");
        Item item = Items.BARRIER;
        if (parts.length > 0) {
            try { item = BuiltInRegistries.ITEM.getValue(Identifier.parse(parts[0])); }
            catch (Exception ignored) { }
        }
        ItemStack icon = new ItemStack(item == null ? Items.BARRIER : item);
        icon.set(DataComponents.CUSTOM_NAME, Component.literal("#" + id + " " + value + " (click to edit)"));
        return icon;
    }

    private static void openRewardsEditor(ServerPlayer admin, String crate, int page) {
        List<Integer> ids = rewardIds(crate);
        int maxPage = Math.max(0, (ids.size() - 1) / 18);
        int current = Math.max(0, Math.min(page, maxPage));
        SimpleContainer view = new SimpleContainer(27);
        for (int i = 0; i < 18 && current * 18 + i < ids.size(); i++)
            view.setItem(i, rewardIcon(crate, ids.get(current * 18 + i)));
        view.setItem(18, button(Items.ARROW, "Previous page"));
        view.setItem(22, button(Items.BARRIER, "Back"));
        view.setItem(26, button(Items.ARROW, "Next page"));
        admin.openMenu(new SimpleMenuProvider((sync, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, sync, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput ContainerInput, Player player) {
                if (slot >= 0 && slot < 27) {
                    int index = current * 18 + slot;
                    if (slot < 18 && index < ids.size()) openRewardDetails(admin, crate, ids.get(index), current);
                    else if (slot == 18) openRewardsEditor(admin, crate, current - 1);
                    else if (slot == 22) openMainEditor(admin, crate);
                    else if (slot == 26) openRewardsEditor(admin, crate, current + 1);
                    return;
                }
                super.clicked(slot, button, ContainerInput, player);
            }
        }, Component.literal("Rewards | " + crate + " | " + (current + 1))));
    }

    private static void openRewardDetails(ServerPlayer admin, String crate, int id, int page) {
        String key = crate + "." + id;
        String value;
        synchronized (REWARDS) { value = REWARDS.getProperty(key); }
        if (value == null) { openRewardsEditor(admin, crate, page); return; }
        String[] parts = value.split("\\|");
        int chance = parseChance(parts);
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(4, rewardIcon(crate, id));
        view.setItem(10, button(Items.REDSTONE, "-10%"));
        view.setItem(11, button(Items.REDSTONE, "-1%"));
        view.setItem(13, button(Items.PAPER, "Chance: " + chance + "%"));
        view.setItem(15, button(Items.EMERALD, "+1%"));
        view.setItem(16, button(Items.EMERALD, "+10%"));
        view.setItem(21, button(Items.LAVA_BUCKET, "Remove Reward"));
        view.setItem(23, button(Items.ARROW, "Back"));
        admin.openMenu(new SimpleMenuProvider((sync, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, sync, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput ContainerInput, Player player) {
                if (slot >= 0 && slot < 27) {
                    if (slot == 23) { openRewardsEditor(admin, crate, page); return; }
                    int delta = slot == 10 ? -10 : slot == 11 ? -1 : slot == 15 ? 1 : slot == 16 ? 10 : 0;
                    if (slot == 21 || delta != 0) {
                        synchronized (REWARDS) {
                            String existing = REWARDS.getProperty(key);
                            if (existing != null) {
                                if (slot == 21) REWARDS.remove(key);
                                else {
                                    String[] fields = existing.split("\\|");
                                    int adjusted = Math.max(0, Math.min(100, parseChance(fields) + delta));
                                    REWARDS.setProperty(key, fields[0] + "|" + (fields.length > 1 ? fields[1] : "1") + "|" + adjusted);
                                }
                                try { saveRewards(); }
                                catch (IOException e) { admin.sendSystemMessage(Component.literal("[AbyssCrate] Save error")); }
                            }
                        }
                        if (slot == 21) openRewardsEditor(admin, crate, page);
                        else openRewardDetails(admin, crate, id, page);
                    }
                    return;
                }
                super.clicked(slot, button, ContainerInput, player);
            }
        }, Component.literal("Edit Reward #" + id)));
    }

    // Reward chances are relative weights: e.g. 10 and 30 => 25% / 75%.
    // Zero-weight rewards cannot be selected. All-zero / missing rewards give nothing.
    private record RewardChoice(Item item, int count, int weight) { }

    private static RewardChoice chooseReward(String crate) {
        List<RewardChoice> choices = new ArrayList<>();
        long total = 0;
        synchronized (REWARDS) {
            for (int id : rewardIds(crate)) {
                String value = REWARDS.getProperty(crate + "." + id, "");
                String[] parts = value.split("\\|");
                if (parts.length < 2) continue;
                try {
                    Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(parts[0]));
                    if (item == null || item == Items.AIR) continue;
                    int count = Integer.parseInt(parts[1]);
                    int weight = parseChance(parts);
                    if (count < 1 || count > 64 || weight <= 0) continue;
                    choices.add(new RewardChoice(item, count, weight));
                    total += weight;
                } catch (RuntimeException ignored) { }
            }
        }
        if (total == 0) return null;
        long roll = ThreadLocalRandom.current().nextLong(total);
        for (RewardChoice choice : choices) {
            roll -= choice.weight();
            if (roll < 0) return choice;
        }
        return null;
    }

    private static boolean hasEmptyInventorySlot(ServerPlayer player) {
        // Only the 36 ordinary player inventory slots (hotbar + main inventory).
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return true;
        }
        return false;
    }

    private static int parseChance(String[] fields) {
        if (fields.length < 3) return 10; // Existing 3E rewards have no chance field.
        try { return Math.max(0, Math.min(100, Integer.parseInt(fields[2]))); }
        catch (NumberFormatException ex) { return 10; }
    }

    private static synchronized void loadRewards() throws IOException {
        Files.createDirectories(DIR);
        Properties next = new Properties();
        if (Files.exists(REWARDS_FILE)) {
            try (InputStream in = Files.newInputStream(REWARDS_FILE)) { next.load(in); }
        }
        REWARDS.clear();
        REWARDS.putAll(next);
    }

    private static synchronized void saveRewards() throws IOException {
        Files.createDirectories(DIR);
        try (OutputStream out = Files.newOutputStream(REWARDS_FILE)) {
            REWARDS.store(out, "AbyssCrate rewards - itemId|count|relativeWeight");
        }
    }

    private static ItemStack createKey(String name, int amount) {
        ItemStack stack = new ItemStack(Items.TRIPWIRE_HOOK, amount);
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_TAG, name);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Cheie " + name.toUpperCase(Locale.ROOT)));
        return stack;
    }

    private static boolean isKeyFor(ItemStack stack, String name) {
        if (!stack.is(Items.TRIPWIRE_HOOK)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().getString(KEY_TAG).map(name::equals).orElse(false);
    }

    private static String locationKey(String dimension, BlockPos pos) {
        return dimension + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static synchronized void loadPlacements() throws IOException {
        Properties next = new Properties();
        if (Files.exists(PLACEMENTS_FILE)) {
            try (InputStream in = Files.newInputStream(PLACEMENTS_FILE)) { next.load(in); }
        }
        synchronized (PLACEMENTS) {
            PLACEMENTS.clear();
            PLACEMENTS.putAll(next);
        }
    }

    private static synchronized void savePlacements() throws IOException {
        Files.createDirectories(DIR);
        try (OutputStream out = Files.newOutputStream(PLACEMENTS_FILE)) {
            PLACEMENTS.store(out, "AbyssCrate Fabric - block positions");
        }
    }

    private static boolean valid(String name) {
        return name.matches("[a-z0-9_-]{1,32}");
    }

    private static void reply(net.minecraft.commands.CommandSourceStack source, String key, String value) {
        String output = message(key, value);
        source.sendSuccess(() -> Component.literal(output), false);
    }

    private static synchronized String message(String key, String value) {
        return MESSAGES.getProperty("prefix", "[AbyssCrate] ") +
            MESSAGES.getProperty(key, key).replace("{crate}", value).replace("{list}", value);
    }

    private static synchronized void loadCrates() throws IOException {
        Properties next = new Properties();
        if (Files.exists(DATA)) {
            try (InputStream in = Files.newInputStream(DATA)) {
                next.load(in);
            }
        }
        synchronized (CRATES) {
            CRATES.clear();
            CRATES.putAll(next);
        }
    }

    private static synchronized void saveCrates() throws IOException {
        Files.createDirectories(DIR);
        try (OutputStream out = Files.newOutputStream(DATA)) {
            CRATES.store(out, "AbyssCrate Fabric - crate types");
        }
    }

    private static synchronized void loadMessages() throws IOException {
        Properties defaults = new Properties();
        defaults.setProperty("prefix", "[AbyssCrate] ");
        defaults.setProperty("block-set", "Bloc asociat crate-ului {crate}.");
        defaults.setProperty("block-unset", "Blocul nu mai este un crate ({crate}).");
        defaults.setProperty("look-at-block", "Priveste un bloc aflat la maximum 5 blocuri distanta.");
        defaults.setProperty("not-a-crate", "Acest bloc nu este un crate.");
        defaults.setProperty("needs-key", "Ai nevoie de cheia pentru crate-ul {crate}.");
        defaults.setProperty("key-valid", "Cheia pentru {crate} este valida.");
        defaults.setProperty("no-rewards", "Crate-ul {crate} nu are recompense cu sansa mai mare de zero.");
        defaults.setProperty("reward-won", "Ai castigat {amount} x {item} din crate-ul {crate}!");
        defaults.setProperty("key-given", "Cheile pentru {crate} au fost acordate.");
        defaults.setProperty("inventory-full", "Inventarul jucatorului este plin.");
        defaults.setProperty("protected", "Nu poti distruge acest crate.");
        defaults.setProperty("created", "Crate creat: {crate}");
        defaults.setProperty("already-exists", "Crate-ul {crate} exista deja.");
        defaults.setProperty("invalid-name", "Nume invalid: foloseste a-z, 0-9, _ sau - (maxim 32).");
        defaults.setProperty("not-found", "Crate-ul {crate} nu exista.");
        defaults.setProperty("removed", "Crate sters: {crate}");
        defaults.setProperty("save-error", "Nu am putut salva crate-ul {crate}.");
        defaults.setProperty("load-error", "Nu am putut reincarca fisierele de configurare.");
        defaults.setProperty("reloaded", "Configuratia a fost reincarcata.");
        defaults.setProperty("empty", "Nu exista crate-uri. Foloseste /crate create <nume>.");
        defaults.setProperty("list", "Crate-uri: {list}");

        Files.createDirectories(DIR);
        if (!Files.exists(MESSAGES_FILE)) {
            try (OutputStream out = Files.newOutputStream(MESSAGES_FILE)) {
                defaults.store(out, "AbyssCrate messages - edit and use /crate reload");
            }
        }
        Properties next = new Properties(defaults);
        try (InputStream in = Files.newInputStream(MESSAGES_FILE)) {
            next.load(in);
        }
        synchronized (MESSAGES) {
            MESSAGES.clear();
            for (String key : defaults.stringPropertyNames()) {
                MESSAGES.setProperty(key, next.getProperty(key));
            }
        }
    }
}
