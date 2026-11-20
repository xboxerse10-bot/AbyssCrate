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
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.world.item.component.ItemLore;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Path SETTINGS_FILE = DIR.resolve("crate-settings.properties");
    private static final Properties SETTINGS = new Properties();
    private static final Path HOLOGRAMS_FILE = DIR.resolve("holograms.properties");
    private static final Properties HOLOGRAMS = new Properties();
    private static final String DEFAULT_GRADIENT_PREFIX = "&#004FFF[&#005CFFA&#0067FFb&#0072FFy&#007CFFs&#0086FFs&#008FFFC&#0098FFr&#00A0FFa&#00A8FFt&#00B0FFe&#00B7FF]&r ";
    private static final String KEY_TAG = "shadowcrates_key_type";
    private static final Map<UUID, MysterySession> ACTIVE_OPENINGS = new HashMap<>();
    private static final int ANIMATION_TICKS = 100;
    private static final int REVEAL_TICKS = 20;
    private static int effectsTick = 0;
    private static int hologramTick = 0;
    private static boolean hologramRefreshNeeded = true;

    private static final class MysterySession {
        final String crate;
        final RewardChoice prize;
        final int duration;
        final SimpleContainer display = new SimpleContainer(27);
        int ticks;
        MysterySession(String crate, RewardChoice prize) {
            this.duration = settingInt(crate, "duration", 100);
            this.crate = crate;
            this.prize = prize;
        }
    }

    @Override
    public void onInitialize() {
        try {
            loadCrates();
            loadMessages();
            loadPlacements();
            loadRewards();
            loadSettings();
            loadHolograms();
        } catch (IOException ex) {
            throw new IllegalStateException("AbyssCrate: configuratia nu a putut fi incarcata", ex);
        }

        // 3O: Left-click opens the public preview; right-click remains key opening.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            String crate;
            synchronized (PLACEMENTS) {
                crate = PLACEMENTS.getProperty(locationKey(world.dimension().identifier().toString(), pos));
            }
            if (crate == null) return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer && settingBool(crate, "preview", true)) {
                openPlayerPreview(serverPlayer, crate, 0);
            } else if (player instanceof ServerPlayer) {
                player.sendSystemMessage(Component.literal("[AbyssCrate] Preview dezactivat pentru acest crate."));
            }
            return InteractionResult.SUCCESS;
        });

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
                player.sendSystemMessage(colored(message("needs-key", name)));
                return InteractionResult.SUCCESS;
            }
            RewardChoice selected = chooseReward(name);
            if (selected == null) {
                player.sendSystemMessage(colored(message("no-rewards", name)));
                return InteractionResult.SUCCESS;
            }
            // For safety, require an empty inventory slot before awarding an item.
            // This prevents accidental item loss and avoids consuming a key on failure.
            if (!hasEmptyInventorySlot(serverPlayer)) {
                player.sendSystemMessage(colored(message("inventory-full", name)));
                return InteractionResult.SUCCESS;
            }
            if (ACTIVE_OPENINGS.containsKey(serverPlayer.getUUID())) {
                player.sendSystemMessage(Component.literal("[AbyssCrate] Ai deja un crate in curs de deschidere."));
                return InteractionResult.SUCCESS;
            }
            if (!settingBool(name, "animation", true)) {
                finishMystery(serverPlayer, new MysterySession(name, selected));
                return InteractionResult.SUCCESS;
            }
            playCrateEffect(serverPlayer, name, "open");
            MysterySession session = new MysterySession(name, selected);
            ACTIVE_OPENINGS.put(serverPlayer.getUUID(), session);
            renderMystery(session, false);
            serverPlayer.openMenu(new SimpleMenuProvider((id, inv, viewer) ->
                new ChestMenu(MenuType.GENERIC_9x3, id, inv, session.display, 3) {
                    @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                        // Prevent moving preview items out of the animated chest.
                        if (slot >= 0 && slot < 27) return;
                        super.clicked(slot, button, input, clicker);
                    }
                }, Component.literal("AbyssCrate | " + displayName(name))));
            return InteractionResult.SUCCESS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Lightweight ambient particles every second, only near players.
            if (++effectsTick >= 20) {
                effectsTick = 0;
                tickAmbientEffects(server);
            }
            // Reconcile floating labels periodically, including after chunk loads.
            if (++hologramTick >= 600 || hologramRefreshNeeded) {
                hologramTick = 0;
                hologramRefreshNeeded = false;
                refreshHolograms(server);
            }
            var iterator = ACTIVE_OPENINGS.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                ServerPlayer openingPlayer = server.getPlayerList().getPlayer(entry.getKey());
                if (openingPlayer == null) {
                    // No key was consumed, so a disconnect cancels safely.
                    iterator.remove();
                    continue;
                }
                MysterySession session = entry.getValue();
                session.ticks++;
                if (session.ticks <= session.duration && session.ticks % animationInterval(session.ticks, session.duration) == 0) {
                    renderMystery(session, false);
                    playCrateEffect(openingPlayer, session.crate, "tick");
                    openingPlayer.containerMenu.broadcastChanges();
                }
                if (session.ticks == session.duration + 1) {
                    renderMystery(session, true);
                    playCrateEffect(openingPlayer, session.crate, "reveal");
                    openingPlayer.containerMenu.broadcastChanges();
                }
                if (session.ticks >= session.duration + REVEAL_TICKS) {
                    finishMystery(openingPlayer, session);
                    iterator.remove();
                }
            }
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            synchronized (PLACEMENTS) {
                if (!PLACEMENTS.containsKey(locationKey(world.dimension().identifier().toString(), pos))) return true;
            }
            if (!world.isClientSide()) player.sendSystemMessage(colored(message("protected", "")));
            return false;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("crate")
                .then(Commands.literal("preview")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String crate = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                            synchronized (CRATES) {
                                if (!CRATES.containsKey(crate)) { reply(ctx.getSource(), "not-found", crate); return 0; }
                            }
                            if (!settingBool(crate, "preview", true)) {
                                ctx.getSource().sendFailure(Component.literal("Preview este dezactivat pentru acest crate."));
                                return 0;
                            }
                            openPlayerPreview(ctx.getSource().getPlayerOrException(), crate, 0);
                            return 1;
                        }))));
            dispatcher.register(Commands.literal("crate")
                .then(Commands.literal("hologram")
                    .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.literal("add")
                            .then(Commands.argument("line", IntegerArgumentType.integer(1, 10))
                            .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String crate = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                                    synchronized (CRATES) {
                                        if (!CRATES.containsKey(crate)) { reply(ctx.getSource(), "not-found", crate); return 0; }
                                    }
                                    setHologramLine(ctx.getSource().getPlayerOrException(), crate,
                                            IntegerArgumentType.getInteger(ctx, "line"),
                                            StringArgumentType.getString(ctx, "text"));
                                    return 1;
                                }))))
                        .then(Commands.literal("remove")
                            .then(Commands.argument("removeLine", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> {
                                    String crate = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                                    synchronized (CRATES) {
                                        if (!CRATES.containsKey(crate)) { reply(ctx.getSource(), "not-found", crate); return 0; }
                                    }
                                    removeHologramLine(ctx.getSource().getPlayerOrException(), crate,
                                            IntegerArgumentType.getInteger(ctx, "removeLine"));
                                    return 1;
                                }))))));
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
                                try { savePlacements(); hologramRefreshNeeded = true; }
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
                        try { savePlacements(); hologramRefreshNeeded = true; }
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
            loadSettings();
            loadHolograms();
                        hologramRefreshNeeded = true;
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
                    ctx.getSource().sendSuccess(() -> colored(message), false);
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
        view.setItem(16, button(Items.COMPARATOR, "Crate Settings"));
        view.setItem(22, button(Items.BARRIER, "Close"));
        admin.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput ContainerInput, Player player) {
                if (slot >= 0 && slot < 27) {
                    if (slot == 10) openAddEditor(admin, crate);
                    else if (slot == 12 || slot == 14) openRewardsEditor(admin, crate, 0);
                    else if (slot == 16) openCrateSettings(admin, crate);
                    else if (slot == 22) admin.closeContainer();
                    return;
                }
                super.clicked(slot, button, ContainerInput, player);
            }
        }, Component.literal("AbyssCrate | " + crate)));
    }

    // 3N: Texts are editable through /crate hologram and holograms.properties.
    private static synchronized void loadHolograms() throws IOException {
        Files.createDirectories(DIR);
        HOLOGRAMS.clear();
        if (Files.exists(HOLOGRAMS_FILE)) {
            try (InputStream in = Files.newInputStream(HOLOGRAMS_FILE)) { HOLOGRAMS.load(in); }
        }
    }

    private static synchronized void saveHolograms() throws IOException {
        Files.createDirectories(DIR);
        try (OutputStream out = Files.newOutputStream(HOLOGRAMS_FILE)) {
            HOLOGRAMS.store(out, "AbyssCrate hologram text; supports &#RRGGBB and &l");
        }
    }

    private static synchronized String hologramValue(String crate, String key, String fallback) {
        return HOLOGRAMS.getProperty(crate + "." + key, fallback);
    }

    private static void setHologramLine(ServerPlayer admin, String crate, int line, String value) {
        if (value.length() > 240 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            admin.sendSystemMessage(Component.literal("[AbyssCrate] Maximum 240 caractere, fara linii noi."));
            return;
        }
        synchronized (HOLOGRAMS) {
            String key = crate + ".line" + line;
            String previous = (String) HOLOGRAMS.setProperty(key, value);
            try { saveHolograms(); hologramRefreshNeeded = true; }
            catch (IOException ex) {
                if (previous == null) HOLOGRAMS.remove(key); else HOLOGRAMS.setProperty(key, previous);
                admin.sendSystemMessage(Component.literal("[AbyssCrate] Nu pot salva holograms.properties"));
                return;
            }
        }
        admin.sendSystemMessage(Component.literal("[AbyssCrate] Randul " + line + " salvat."));
    }

    private static void removeHologramLine(ServerPlayer admin, String crate, int line) {
        synchronized (HOLOGRAMS) {
            String key = crate + ".line" + line;
            // Blank marker also suppresses the default text on lines 1-3.
            String previous = (String) HOLOGRAMS.setProperty(key, "");
            try { saveHolograms(); hologramRefreshNeeded = true; }
            catch (IOException ex) {
                if (previous == null) HOLOGRAMS.remove(key); else HOLOGRAMS.setProperty(key, previous);
                admin.sendSystemMessage(Component.literal("[AbyssCrate] Nu pot salva holograms.properties"));
                return;
            }
        }
        admin.sendSystemMessage(Component.literal("[AbyssCrate] Randul " + line + " a fost sters."));
    }

    private static void openHologramEditor(ServerPlayer admin, String crate) {
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(10, button(Items.NAME_TAG, "Line 1: /crate hologram " + crate + " 1 <text>"));
        view.setItem(11, button(Items.PAPER, "Line 2: /crate hologram " + crate + " 2 <text>"));
        view.setItem(12, button(Items.PAPER, "Lines 1-10: /crate hologram " + crate + " <1-10> <text>"));
        view.setItem(14, button(Items.ARROW, "Height: " + settingInt(crate, "hologram-height", 0) + " cm (click +10, right click -10)"));
        view.setItem(15, button(Items.SPYGLASS, "Scale: " + settingInt(crate, "hologram-scale", 50) + "% (click +10, right click -10)"));
        view.setItem(22, button(Items.BARRIER, "Back to Settings"));
        admin.openMenu(new SimpleMenuProvider((id, inv, viewer) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                if (slot >= 0 && slot < 27) {
                    if (slot == 14) {
                        int v = settingInt(crate, "hologram-height", 0);
                        changeHologramSetting(admin, crate, "hologram-height", Math.max(-100, Math.min(200, v + (button == 1 ? -10 : 10))));
                    } else if (slot == 15) {
                        int v = settingInt(crate, "hologram-scale", 50);
                        changeHologramSetting(admin, crate, "hologram-scale", Math.max(20, Math.min(150, v + (button == 1 ? -10 : 10))));
                    } else if (slot == 22) openCrateSettings(admin, crate);
                    return;
                }
                super.clicked(slot, button, input, clicker);
            }
        }, Component.literal("AbyssCrate Hologram | " + crate)));
    }

    private static void changeHologramSetting(ServerPlayer admin, String crate, String key, int value) {
        synchronized (SETTINGS) {
            String prop = crate + "." + key;
            String previous = (String) SETTINGS.setProperty(prop, Integer.toString(value));
            try { saveSettings(); hologramRefreshNeeded = true; }
            catch (IOException ex) {
                if (previous == null) SETTINGS.remove(prop); else SETTINGS.setProperty(prop, previous);
                admin.sendSystemMessage(Component.literal("[AbyssCrate] Eroare la salvare."));
            }
        }
        openHologramEditor(admin, crate);
    }

    private static String snbtQuote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String hologramComponent(String text, String fallbackColor) {
        // Emit an SNBT text-component compound with per-segment HEX colors.
        java.util.List<String> segments = new ArrayList<>();
        Pattern pattern = Pattern.compile("(?i)&#([0-9a-f]{6})|&([lor])");
        Matcher matcher = pattern.matcher(text);
        String color = fallbackColor;
        boolean bold = false;
        int start = 0;
        while (matcher.find()) {
            if (matcher.start() > start) {
                segments.add("{text:" + snbtQuote(text.substring(start, matcher.start())) + ",color:" + snbtQuote(color) + ",bold:" + bold + "}");
            }
            if (matcher.group(1) != null) color = "#" + matcher.group(1).toUpperCase(Locale.ROOT);
            else if (matcher.group(2).equalsIgnoreCase("l")) bold = true;
            else if (matcher.group(2).equalsIgnoreCase("r")) { bold = false; color = fallbackColor; }
            start = matcher.end();
        }
        if (start < text.length()) segments.add("{text:" + snbtQuote(text.substring(start)) + ",color:" + snbtQuote(color) + ",bold:" + bold + "}");
        if (segments.isEmpty()) return "{text:\"\"}";
        if (segments.size() == 1) return segments.get(0);
        return "{text:\"\",extra:[" + String.join(",", segments) + "]}";
    }

    // 3J: Per-crate settings persisted independently of rewards and placements.
    private static synchronized void loadSettings() throws IOException {
        Files.createDirectories(DIR);
        SETTINGS.clear();
        if (Files.exists(SETTINGS_FILE)) {
            try (InputStream in = Files.newInputStream(SETTINGS_FILE)) { SETTINGS.load(in); }
        }
    }

    private static synchronized void saveSettings() throws IOException {
        Files.createDirectories(DIR);
        try (OutputStream out = Files.newOutputStream(SETTINGS_FILE)) {
            SETTINGS.store(out, "AbyssCrate 3J per-crate settings");
        }
    }

    private static synchronized String setting(String crate, String key, String fallback) {
        return SETTINGS.getProperty(crate + "." + key, fallback);
    }

    private static boolean settingBool(String crate, String key, boolean fallback) {
        return Boolean.parseBoolean(setting(crate, key, Boolean.toString(fallback)));
    }

    private static int settingInt(String crate, String key, int fallback) {
        try { return Integer.parseInt(setting(crate, key, Integer.toString(fallback))); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static String displayName(String crate) {
        return setting(crate, "name", crate);
    }

    private static void changeSetting(ServerPlayer admin, String crate, String key, String value) {
        synchronized (SETTINGS) {
            String prop = crate + "." + key;
            String old = (String) SETTINGS.setProperty(prop, value);
            try { saveSettings(); hologramRefreshNeeded = true; }
            catch (IOException ex) {
                if (old == null) SETTINGS.remove(prop);
                else SETTINGS.setProperty(prop, old);
                admin.sendSystemMessage(Component.literal("[AbyssCrate] Eroare la salvarea setarilor."));
            }
        }
        openCrateSettings(admin, crate);
    }

    private static void openPlayerPreview(ServerPlayer player, String crate) {
        openPlayerPreview(player, crate, 0);
    }

    private static void openPlayerPreview(ServerPlayer player, String crate, int page) {
        List<Integer> ids = rewardIds(crate);
        int maxPage = Math.max(0, (ids.size() - 1) / 45);
        int current = Math.max(0, Math.min(page, maxPage));
        SimpleContainer view = new SimpleContainer(54);
        int totalWeight = 0;
        for (int rewardId : ids) {
            String raw;
            synchronized (REWARDS) { raw = REWARDS.getProperty(crate + "." + rewardId, ""); }
            String[] parts = raw.split("\\|");
            if (parts.length >= 2) totalWeight += Math.max(0, parseChance(parts));
        }
        for (int i = 0; i < 45 && current * 45 + i < ids.size(); i++) {
            int rewardId = ids.get(current * 45 + i);
            String raw;
            synchronized (REWARDS) { raw = REWARDS.getProperty(crate + "." + rewardId, ""); }
            String[] parts = raw.split("\\|");
            ItemStack icon = rewardIcon(crate, rewardId);
            int amount = 1;
            if (parts.length >= 2) {
                try { amount = Math.max(1, Math.min(64, Integer.parseInt(parts[1]))); }
                catch (NumberFormatException ignored) { }
            }
            icon.setCount(amount);
            int weight = parts.length >= 2 ? Math.max(0, parseChance(parts)) : 0;
            double percent = totalWeight > 0 ? 100.0 * weight / totalWeight : 0.0;
            icon.set(DataComponents.CUSTOM_NAME, Component.literal("Reward #" + rewardId));
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Sansa: " + String.format(Locale.ROOT, "%.2f", percent) + "%"),
                Component.literal("Cantitate: " + amount),
                Component.literal("Doar vizualizare")
            )));
            view.setItem(i, icon);
        }
        if (current > 0) view.setItem(45, button(Items.ARROW, "Pagina anterioara"));
        view.setItem(49, button(Items.BOOK, "Pagina " + (current + 1) + "/" + (maxPage + 1) + " | " + ids.size() + " recompense"));
        if (current < maxPage) view.setItem(53, button(Items.ARROW, "Pagina urmatoare"));
        player.openMenu(new SimpleMenuProvider((id, inv, viewer) -> new ChestMenu(MenuType.GENERIC_9x6, id, inv, view, 6) {
            @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                if (slot == 45 && current > 0) {
                    openPlayerPreview(player, crate, current - 1);
                    return;
                }
                if (slot == 53 && current < maxPage) {
                    openPlayerPreview(player, crate, current + 1);
                    return;
                }
                // No movement into/out of the preview, including shift-click or hotbar swaps.
                if (slot >= 0 && slot < 54) return;
                super.clicked(slot, button, input, clicker);
            }
            @Override public ItemStack quickMoveStack(Player clicker, int slot) {
                return ItemStack.EMPTY;
            }
        }, Component.literal("Preview | " + displayName(crate) + " | " + (current + 1) + "/" + (maxPage + 1))));
    }

    private static void openCrateSettings(ServerPlayer admin, String crate) {
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(10, button(Items.NAME_TAG, "Display Name: " + displayName(crate) + " (click: cycle)"));
        view.setItem(11, button(Items.NETHER_STAR, "Animation: " + (settingBool(crate, "animation", true) ? "ON" : "OFF")));
        view.setItem(12, button(Items.CLOCK, "Duration: " + (settingInt(crate, "duration", 100) / 20) + " seconds"));
        view.setItem(13, button(Items.NOTE_BLOCK, "Sounds: " + (settingBool(crate, "sounds", true) ? "ON" : "OFF") + " (click to toggle)"));
        view.setItem(16, button(Items.BLAZE_POWDER, "Particles: " + (settingBool(crate, "effects", true) ? "ON" : "OFF") + " (click to toggle)"));
        view.setItem(21, button(Items.AMETHYST_SHARD, "Particle Style: " + effectStyle(crate) + " (click to cycle)"));
        view.setItem(14, button(Items.PAPER, "Broadcast Win: " + (settingBool(crate, "broadcast", false) ? "ON" : "OFF")));
        view.setItem(15, button(Items.CHEST, "Preview Rewards: " + (settingBool(crate, "preview", true) ? "ON" : "OFF")));
        view.setItem(20, button(Items.END_ROD, "Hologram: " + (settingBool(crate, "hologram", true) ? "ON" : "OFF")));
        view.setItem(19, button(Items.OAK_SIGN, "Hologram Editor"));
        view.setItem(22, button(Items.ARROW, "Back"));
        admin.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                if (slot >= 0 && slot < 27) {
                    switch (slot) {
                        case 10 -> {
                            String current = displayName(crate);
                            String next = current.equals(crate) ? "AbyssCrate " + crate : crate;
                            changeSetting(admin, crate, "name", next);
                        }
                        case 11 -> changeSetting(admin, crate, "animation", Boolean.toString(!settingBool(crate, "animation", true)));
                        case 12 -> {
                            int seconds = settingInt(crate, "duration", 100) / 20;
                            changeSetting(admin, crate, "duration", Integer.toString((seconds >= 10 ? 3 : seconds + 1) * 20));
                        }
                        case 13 -> changeSetting(admin, crate, "sounds", Boolean.toString(!settingBool(crate, "sounds", true)));
                        case 16 -> changeSetting(admin, crate, "effects", Boolean.toString(!settingBool(crate, "effects", true)));
                        case 21 -> changeSetting(admin, crate, "effect-style", switch (effectStyle(crate)) {
                            case "MAGIC_BLUE" -> "LEGENDARY_GOLD";
                            case "LEGENDARY_GOLD" -> "DARK_ABYSS";
                            default -> "MAGIC_BLUE";
                        });
                        case 14 -> changeSetting(admin, crate, "broadcast", Boolean.toString(!settingBool(crate, "broadcast", false)));
                        case 15 -> changeSetting(admin, crate, "preview", Boolean.toString(!settingBool(crate, "preview", true)));
                        case 20 -> changeSetting(admin, crate, "hologram", Boolean.toString(!settingBool(crate, "hologram", true)));
                        case 19 -> openHologramEditor(admin, crate);
                        case 22 -> openMainEditor(admin, crate);
                        default -> { }
                    }
                    return;
                }
                super.clicked(slot, button, input, clicker);
            }
        }, Component.literal("AbyssCrate Settings | " + crate)));
    }


    // 3M: Effects are server-side commands; no client mod or extra dependency needed.
    // Each crate has independent toggles and one of three visual themes.
    private static String effectStyle(String crate) {
        String style = setting(crate, "effect-style", "MAGIC_BLUE").toUpperCase(Locale.ROOT);
        return switch (style) {
            case "LEGENDARY_GOLD", "DARK_ABYSS" -> style;
            default -> "MAGIC_BLUE";
        };
    }

    private static String effectParticle(String crate) {
        return switch (effectStyle(crate)) {
            case "LEGENDARY_GOLD" -> "minecraft:flame";
            case "DARK_ABYSS" -> "minecraft:portal";
            default -> "minecraft:soul_fire_flame";
        };
    }

    private static String effectSound(String crate, String phase) {
        if ("tick".equals(phase)) return "minecraft:block.note_block.hat";
        if ("win".equals(phase) || "reveal".equals(phase)) return "minecraft:entity.player.levelup";
        return switch (effectStyle(crate)) {
            case "LEGENDARY_GOLD" -> "minecraft:block.amethyst_block.chime";
            case "DARK_ABYSS" -> "minecraft:block.portal.trigger";
            default -> "minecraft:block.enchantment_table.use";
        };
    }

    private static void playCrateEffect(ServerPlayer player, String crate, String phase) {
        var server = player.level().getServer();
        if (server == null) return;
        String name = player.getGameProfile().name();
        // Players' names are Minecraft-validated; the command never contains config text.
        if (!name.matches("[A-Za-z0-9_]{1,16}")) return;
        if (settingBool(crate, "sounds", true)) {
            String sound = effectSound(crate, phase);
            String pitch = "tick".equals(phase) ? "1.5" : "1";
            runHologramCommand(server, "execute as " + name + " at @s run playsound " + sound
                    + " master @s ~ ~ ~ 0.6 " + pitch);
        }
        if (settingBool(crate, "effects", true) && !"tick".equals(phase)) {
            runHologramCommand(server, "execute as " + name + " at @s run particle "
                    + effectParticle(crate) + " ~ ~1 ~ 0.35 0.5 0.35 0.04 16 force");
        }
    }

    private static void tickAmbientEffects(net.minecraft.server.MinecraftServer server) {
        Map<String, String> snapshot = new HashMap<>();
        synchronized (PLACEMENTS) {
            for (String key : PLACEMENTS.stringPropertyNames()) snapshot.put(key, PLACEMENTS.getProperty(key));
        }
        for (var entry : snapshot.entrySet()) {
            String crate = entry.getValue();
            if (!settingBool(crate, "effects", true)) continue;
            String[] parts = entry.getKey().split("\\|", 2);
            if (parts.length != 2 || !parts[0].matches("[a-z0-9_:.\\/-]+")) continue;
            String[] xyz = parts[1].split(",");
            if (xyz.length != 3) continue;
            try {
                int x = Integer.parseInt(xyz[0]);
                int y = Integer.parseInt(xyz[1]);
                int z = Integer.parseInt(xyz[2]);
                String pos = (x + 0.5) + " " + (y + 1.1) + " " + (z + 0.5);
                runHologramCommand(server, "execute in " + parts[0] + " positioned " + pos
                        + " if entity @a[distance=..24] run particle " + effectParticle(crate)
                        + " ~ ~ ~ 0.25 0.3 0.25 0.01 3 normal");
            } catch (NumberFormatException ignored) { }
        }
    }

    // 3L.2: Text Display holograms. Text is a structured component in modern Minecraft.
    private static void refreshHolograms(net.minecraft.server.MinecraftServer server) {
        Map<String, String> snapshot = new HashMap<>();
        synchronized (PLACEMENTS) {
            for (String key : PLACEMENTS.stringPropertyNames()) snapshot.put(key, PLACEMENTS.getProperty(key));
        }
        for (var entry : snapshot.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            if (parts.length != 2) continue;
            String[] xyz = parts[1].split(",");
            if (xyz.length != 3) continue;
            try {
                int x = Integer.parseInt(xyz[0]);
                int y = Integer.parseInt(xyz[1]);
                int z = Integer.parseInt(xyz[2]);
                String dim = parts[0];
                if (!dim.matches("[a-z0-9_:.\\/-]+")) continue;
                String tag = "abyss_h_" + Integer.toUnsignedString(entry.getKey().hashCode(), 36);
                String center = (x + 0.5) + " " + (y + 1.75) + " " + (z + 0.5);
                // Clean up both previous 3L displays and the new displays for this crate.
                // Scoped to the placement: never delete other plugins' text displays.
                runHologramCommand(server, "execute in " + dim + " positioned " + center
                        + " run kill @e[type=minecraft:text_display,tag=abysscrate_hologram,distance=..3]");
                if (!settingBool(entry.getValue(), "hologram", true)) continue;
                String crate = entry.getValue();
                String[] defaults = {
                    "&l✦ " + displayName(crate) + " ✦",
                    "Right Click to Open",
                    "Left Click to Preview"
                };
                String[] colors = {"#00B7FF", "#FFFFFF", "#AAAAAA"};
                java.util.List<String> visibleLines = new java.util.ArrayList<>();
                java.util.List<String> visibleColors = new java.util.ArrayList<>();
                for (int lineNumber = 1; lineNumber <= 10; lineNumber++) {
                    String fallback = lineNumber <= 3 ? defaults[lineNumber - 1] : "";
                    String value = hologramValue(crate, "line" + lineNumber, fallback);
                    if (!value.isBlank()) {
                        visibleLines.add(value);
                        visibleColors.add(lineNumber <= 3 ? colors[lineNumber - 1] : "#FFFFFF");
                    }
                }
                double height = Math.max(-100, Math.min(200, settingInt(crate, "hologram-height", 0))) / 100.0;
                float scale = Math.max(20, Math.min(150, settingInt(crate, "hologram-scale", 50))) / 100.0f;
                // Keep the first line at the original height and stack later lines below it.
                // Increase separation proportionally with the configured text scale.
                double spacing = 0.44 * scale;
                for (int line = 0; line < visibleLines.size(); line++) {
                    String pos = (x + 0.5) + " " + (y + 1.75 + 0.28 + height - line * spacing) + " " + (z + 0.5);
                    String nbt = "{Tags:[\"abysscrate_hologram\",\"" + tag + "\"],"
                            + "billboard:\"center\",alignment:\"center\",see_through:1b,"
                            + "background:0,shadow:1b,"
                            + "transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],"
                            + "scale:[" + scale + "f," + scale + "f," + scale + "f],right_rotation:[0f,0f,0f,1f]},"
                            + "text:" + hologramComponent(visibleLines.get(line), visibleColors.get(line)) + "}";
                    runHologramCommand(server, "execute in " + dim + " positioned " + pos
                            + " run summon minecraft:text_display ~ ~ ~ " + nbt);
                }
            } catch (RuntimeException ex) {
                System.err.println("[AbyssCrate] Hologram refresh failed: " + ex.getMessage());
            }
        }
    }

    private static void runHologramCommand(net.minecraft.server.MinecraftServer server, String command) {
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        } catch (RuntimeException ex) {
            System.err.println("[AbyssCrate] Hologram command failed: " + ex.getMessage());
        }
    }

    // 3I: Add up to 18 different reward stacks in one operation.
    // The top two rows are editable; the last row is reserved for controls.
    private static void openAddEditor(ServerPlayer admin, String crate) {
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(22, button(Items.EMERALD, "Save All - close this menu"));
        admin.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            private boolean saved = false;

            @Override public void clicked(int slot, int button, ContainerInput clickType, Player player) {
                if (slot >= 18 && slot < 27) {
                    if (slot == 22) admin.closeContainer();
                    return;
                }
                super.clicked(slot, button, clickType, player);
            }

            @Override public void removed(Player player) {
                if (!saved) {
                    saved = true;
                    List<ItemStack> pending = new ArrayList<>();
                    for (int slot = 0; slot < 18; slot++) {
                        ItemStack stack = view.getItem(slot);
                        if (!stack.isEmpty()) pending.add(stack.copy());
                    }
                    if (!pending.isEmpty()) {
                        int savedCount = 0;
                        try {
                            synchronized (REWARDS) {
                                // Build all entries first; persist only once.
                                Properties backup = new Properties();
                                backup.putAll(REWARDS);
                                int next = 1;
                                for (ItemStack reward : pending) {
                                    while (REWARDS.containsKey(crate + "." + next)) next++;
                                    String itemId = BuiltInRegistries.ITEM.getKey(reward.getItem()).toString();
                                    REWARDS.setProperty(crate + "." + next, itemId + "|" + reward.getCount() + "|10");
                                    next++;
                                }
                                try {
                                    saveRewards();
                                    savedCount = pending.size();
                                } catch (IOException ex) {
                                    REWARDS.clear();
                                    REWARDS.putAll(backup);
                                    throw ex;
                                }
                            }
                            player.sendSystemMessage(Component.literal("[AbyssCrate] " + savedCount + " rewards saved for " + crate + "."));
                        } catch (IOException ex) {
                            player.sendSystemMessage(Component.literal("[AbyssCrate] Could not save rewards; no changes applied."));
                        }
                    }
                    // Return the original stacks; the editor must never consume admin items.
                    for (int slot = 0; slot < 18; slot++) {
                        ItemStack original = view.removeItemNoUpdate(slot);
                        if (!original.isEmpty()) player.getInventory().add(original);
                    }
                }
                super.removed(player);
            }
        }, Component.literal("Add Multiple Rewards | " + crate)));
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

    private static int animationInterval(int ticks, int duration) {
        if (ticks < duration * 0.4) return 3;
        if (ticks < duration * 0.7) return 5;
        return 10;
    }

    private static void renderMystery(MysterySession session, boolean reveal) {
        for (int slot = 0; slot < 27; slot++) session.display.setItem(slot, ItemStack.EMPTY);
        session.display.setItem(4, button(Items.NETHER_STAR, reveal ? "Recompensa castigatoare!" : "AbyssCrate - Se deschide..."));
        for (int slot = 9; slot < 18; slot++) {
            RewardChoice candidate = reveal && slot == 13 ? session.prize : chooseReward(session.crate);
            if (candidate != null) session.display.setItem(slot, new ItemStack(candidate.item(), candidate.count()));
        }
        if (reveal) {
            session.display.setItem(22, button(Items.EMERALD, "Felicitari!"));
        } else {
            session.display.setItem(22, button(Items.CLOCK, "Asteapta rezultatul..."));
        }
    }

    private static void finishMystery(ServerPlayer player, MysterySession session) {
        // A key is taken only when the reward can actually be delivered.
        if (!hasEmptyInventorySlot(player)) {
            player.sendSystemMessage(colored(message("inventory-full", session.crate)));
            return;
        }
        int keySlot = -1;
        for (int slot = 0; slot < 36; slot++) {
            if (isKeyFor(player.getInventory().getItem(slot), session.crate)) {
                keySlot = slot;
                break;
            }
        }
        if (keySlot == -1) {
            player.sendSystemMessage(colored(message("needs-key", session.crate)));
            return;
        }
        ItemStack prize = new ItemStack(session.prize.item(), session.prize.count());
        String displayName = prize.getHoverName().getString();
        if (!player.getInventory().add(prize)) {
            player.sendSystemMessage(colored(message("inventory-full", session.crate)));
            return;
        }
        player.getInventory().getItem(keySlot).shrink(1);
        playCrateEffect(player, session.crate, "win");
        String winText = message("reward-won", session.crate)
            .replace("{item}", displayName)
            .replace("{amount}", Integer.toString(session.prize.count()));
        if (settingBool(session.crate, "broadcast", false)) {
            String broadcast = MESSAGES.getProperty("broadcast-win", "{prefix}Jucatorul {player} a castigat {amount} x {item} din crate-ul {crate}!")
                .replace("{prefix}", MESSAGES.getProperty("prefix", DEFAULT_GRADIENT_PREFIX))
                .replace("{player}", player.getName().getString())
                .replace("{amount}", Integer.toString(session.prize.count()))
                .replace("{item}", displayName)
                .replace("{crate}", session.crate);
            Component announcement = colored(broadcast);
            for (ServerPlayer onlinePlayer : player.level().getServer().getPlayerList().getPlayers()) {
                onlinePlayer.sendSystemMessage(announcement);
            }
        } else player.sendSystemMessage(colored(winText));
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
        source.sendSuccess(() -> colored(output), false);
    }

    // 3K: Minecraft text formatting for messages.properties.
    // Supports &#RRGGBB, &l (bold), &o (italic), &n (underline), &m (strikethrough),
    // &r (reset), and <gradient:#RRGGBB:#RRGGBB>text</gradient>.
    private static final Pattern GRADIENT = Pattern.compile("(?i)<gradient:#([0-9a-f]{6}):#([0-9a-f]{6})>(.*?)</gradient>", Pattern.DOTALL);
    private static final Pattern COLOR_TOKEN = Pattern.compile("(?i)&#([0-9a-f]{6})|&([0-9a-fk-or])");

    private static Component colored(String input) {
        var output = Component.empty();
        Matcher gradient = GRADIENT.matcher(input);
        int pos = 0;
        while (gradient.find()) {
            appendFormatted(output, input.substring(pos, gradient.start()));
            String body = gradient.group(3);
            int start = Integer.parseInt(gradient.group(1), 16);
            int end = Integer.parseInt(gradient.group(2), 16);
            int length = body.codePointCount(0, body.length());
            int index = 0;
            for (int offset = 0; offset < body.length();) {
                int cp = body.codePointAt(offset);
                double t = length <= 1 ? 0.0 : (double) index / (length - 1);
                int red = (int) Math.round(((start >> 16) & 255) * (1 - t) + ((end >> 16) & 255) * t);
                int green = (int) Math.round(((start >> 8) & 255) * (1 - t) + ((end >> 8) & 255) * t);
                int blue = (int) Math.round((start & 255) * (1 - t) + (end & 255) * t);
                output.append(Component.literal(new String(Character.toChars(cp)))
                    .withStyle(style -> style.withColor((red << 16) | (green << 8) | blue)));
                offset += Character.charCount(cp);
                index++;
            }
            pos = gradient.end();
        }
        appendFormatted(output, input.substring(pos));
        return output;
    }

    private static void appendFormatted(net.minecraft.network.chat.MutableComponent output, String input) {
        Matcher token = COLOR_TOKEN.matcher(input);
        net.minecraft.network.chat.Style style = net.minecraft.network.chat.Style.EMPTY;
        int pos = 0;
        while (token.find()) {
            if (token.start() > pos) {
                output.append(Component.literal(input.substring(pos, token.start())).setStyle(style));
            }
            if (token.group(1) != null) {
                int rgb = Integer.parseInt(token.group(1), 16);
                style = style.withColor(rgb);
            } else {
                char code = Character.toLowerCase(token.group(2).charAt(0));
                switch (code) {
                    case 'l' -> style = style.withBold(true);
                    case 'o' -> style = style.withItalic(true);
                    case 'n' -> style = style.withUnderlined(true);
                    case 'm' -> style = style.withStrikethrough(true);
                    case 'k' -> style = style.withObfuscated(true);
                    case 'r' -> style = net.minecraft.network.chat.Style.EMPTY;
                    default -> {
                        int rgb = switch (code) {
                            case '0' -> 0x000000; case '1' -> 0x0000AA; case '2' -> 0x00AA00;
                            case '3' -> 0x00AAAA; case '4' -> 0xAA0000; case '5' -> 0xAA00AA;
                            case '6' -> 0xFFAA00; case '7' -> 0xAAAAAA; case '8' -> 0x555555;
                            case '9' -> 0x5555FF; case 'a' -> 0x55FF55; case 'b' -> 0x55FFFF;
                            case 'c' -> 0xFF5555; case 'd' -> 0xFF55FF; case 'e' -> 0xFFFF55;
                            default -> 0xFFFFFF;
                        };
                        style = net.minecraft.network.chat.Style.EMPTY.withColor(rgb);
                    }
                }
            }
            pos = token.end();
        }
        if (pos < input.length()) output.append(Component.literal(input.substring(pos)).setStyle(style));
    }

    private static synchronized String message(String key, String value) {
        return MESSAGES.getProperty("prefix", DEFAULT_GRADIENT_PREFIX) +
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
        defaults.setProperty("prefix", DEFAULT_GRADIENT_PREFIX);
        defaults.setProperty("broadcast-win", "{prefix}Jucatorul {player} a castigat {amount} x {item} din crate-ul {crate}!");
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
        // Upgrade only the original unformatted prefix; preserve all custom HEX/gradient prefixes.
        String loadedPrefix = next.getProperty("prefix", DEFAULT_GRADIENT_PREFIX);
        if (loadedPrefix.trim().equals("[AbyssCrate]")) {
            next.setProperty("prefix", DEFAULT_GRADIENT_PREFIX);
            // Persist migration so subsequent reloads and restarts use the same gradient.
            Properties stored = new Properties();
            try (InputStream in = Files.newInputStream(MESSAGES_FILE)) {
                stored.load(in);
            }
            stored.setProperty("prefix", DEFAULT_GRADIENT_PREFIX);
            try (OutputStream out = Files.newOutputStream(MESSAGES_FILE)) {
                stored.store(out, "AbyssCrate messages - edit and use /crate reload");
            }
        }
        synchronized (MESSAGES) {
            MESSAGES.clear();
            for (String key : defaults.stringPropertyNames()) {
                MESSAGES.setProperty(key, next.getProperty(key));
            }
        }
    }
}
