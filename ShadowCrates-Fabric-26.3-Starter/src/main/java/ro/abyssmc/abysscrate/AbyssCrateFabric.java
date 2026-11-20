package ro.abyssmc.abysscrate;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
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
            // Nu consumam cheia pana cand sistemul de recompense este implementat.
            player.sendSystemMessage(Component.literal(message("key-valid", name)));
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
                            SimpleContainer inventory = new SimpleContainer(27);
                            ItemStack info = new ItemStack(Items.PAPER);
                            info.set(DataComponents.CUSTOM_NAME, Component.literal("Pune un item in slotul de sub aceasta hartie, apoi inchide meniul"));
                            inventory.setItem(4, info);
                            ItemStack hint = new ItemStack(Items.BARRIER);
                            hint.set(DataComponents.CUSTOM_NAME, Component.literal("Slot de adaugare: mijloc, randul 2 (slot 13)"));
                            inventory.setItem(22, hint);
                            admin.openMenu(new SimpleMenuProvider(
                                (syncId, playerInventory, player) -> new ChestMenu(
                                    net.minecraft.world.inventory.MenuType.GENERIC_9x3, syncId, playerInventory, inventory, 3) {
                                    @Override
                                    public void removed(Player closingPlayer) {
                                        // Salvam copia itemului depus in slotul 13.
                                        ItemStack reward = inventory.getItem(13).copy();
                                        if (!reward.isEmpty()) {
                                            try {
                                                String itemId = BuiltInRegistries.ITEM.getKey(reward.getItem()).toString();
                                                synchronized (REWARDS) {
                                                    int next = 1;
                                                    while (REWARDS.containsKey(name + "." + next)) next++;
                                                    REWARDS.setProperty(name + "." + next, itemId + "|" + reward.getCount());
                                                    saveRewards();
                                                }
                                                closingPlayer.sendSystemMessage(Component.literal("[AbyssCrate] Recompensa salvata pentru " + name + ": " + itemId + " x" + reward.getCount()));
                                            } catch (IOException ex) {
                                                closingPlayer.sendSystemMessage(Component.literal("[AbyssCrate] Eroare la salvarea recompensei."));
                                            }
                                        }
                                        // Returnam itemele reale, ca editorul sa nu le piarda.
                                        inventory.setItem(4, ItemStack.EMPTY);
                                        inventory.setItem(22, ItemStack.EMPTY);
                                        for (int slot = 0; slot < 27; slot++) {
                                            ItemStack stack = inventory.removeItemNoUpdate(slot);
                                            if (!stack.isEmpty() && !closingPlayer.getInventory().add(stack)) {
                                                closingPlayer.drop(stack, false);
                                            }
                                        }
                                        super.removed(closingPlayer);
                                    }
                                },
                                Component.literal("AbyssCrate | " + name)));
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
            REWARDS.store(out, "AbyssCrate reward prototypes - itemId|count");
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
        defaults.setProperty("key-valid", "Cheia pentru {crate} este valida! Recompensele vor fi adaugate in etapa urmatoare.");
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
