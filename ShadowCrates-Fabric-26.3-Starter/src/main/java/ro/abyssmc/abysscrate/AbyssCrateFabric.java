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
    // 3R: UUID-based persistent opening statistics and bounded reward history.
    private static final Path STATS_DIR = Path.of("config", "AbyssCrate", "statistics");
    private static final Path STATS_FILE = STATS_DIR.resolve("openings.properties");
    private static final Path HISTORY_FILE = STATS_DIR.resolve("history.properties");
    private static final Properties STATS = new Properties();
    // 3S.1: One point for every successfully completed crate opening.
    private static final Path MILESTONES_FILE = Path.of("config", "AbyssCrate", "milestones.properties");
    private static final Properties MILESTONES = new Properties();
    private static final Properties HISTORY = new Properties();
    // 3R.5: Two independently positioned, persistent live leaderboard holograms.
    private static final Path TOP_HOLOGRAMS_FILE = Path.of("config", "AbyssCrate", "leaderboard-holograms.properties");
    private static final Properties TOP_HOLOGRAMS = new Properties();
    private static final int MAX_HISTORY = 100;
    private static final Path DIR = Path.of("config", "AbyssCrate");
    private static final Path DATA = DIR.resolve("crates.properties");
    private static final Path MESSAGES_FILE = DIR.resolve("messages.properties");
    private static final Properties CRATES = new Properties();
    private static final Properties MESSAGES = new Properties();
    private static final Properties PLACEMENTS = new Properties();
    private static final Path PLACEMENTS_FILE = DIR.resolve("placements.properties");
    private static final Path REWARDS_FILE = DIR.resolve("rewards.properties");
    private static final Properties REWARDS = new Properties();
    private static final Path RARITIES_FILE = DIR.resolve("reward-rarities.properties");
    private static final Properties RARITIES = new Properties();
    private static final Path SETTINGS_FILE = DIR.resolve("crate-settings.properties");
    private static final Properties SETTINGS = new Properties();
    private static final Path HUB_FILE = DIR.resolve("menu.properties");
    private static final Properties HUB = new Properties();
    private static final Path HOLOGRAMS_FILE = DIR.resolve("holograms.properties");
    private static final Properties HOLOGRAMS = new Properties();
    private static final String DEFAULT_GRADIENT_PREFIX = "&#004FFF[&#005CFFA&#0067FFb&#0072FFy&#007CFFs&#0086FFs&#008FFFC&#0098FFr&#00A0FFa&#00A8FFt&#00B0FFe&#00B7FF]&r ";
    private static final Path KEYS_FILE = DIR.resolve("keys.properties");
    private static final Properties KEYS = new Properties();
    private static final String KEY_TAG = "shadowcrates_key_type";
    private static final Map<UUID, MysterySession> ACTIVE_OPENINGS = new HashMap<>();
    // 4G.1: pending invitations are kept server-side; never trust GUI inventory items.
    private static final Map<UUID, BattleInvite> BATTLE_INVITES = new HashMap<>();
    private static final long BATTLE_INVITE_TIMEOUT_MS = 60_000L;
    private record BattleInvite(UUID challenger, UUID target, String crate, long expiresAt) { }
    // 4G.2: visual-only animation; rewards and milestones settle at acceptance, once.
    private static final Map<UUID, BattleVisual> BATTLE_VISUALS = new HashMap<>();
    private static final class BattleVisual {
        final UUID opponent;
        final String crate;
        final RewardChoice own, rival;
        final boolean won;
        final SimpleContainer display = new SimpleContainer(27);
        int ticks;
        BattleVisual(UUID opponent, String crate, RewardChoice own, RewardChoice rival, boolean won) {
            this.opponent = opponent; this.crate = crate; this.own = own; this.rival = rival; this.won = won;
        }
    }
    private static final int ANIMATION_TICKS = 100;
    private static final int REVEAL_TICKS = 20;
    private static int effectsTick = 0;
    private static int hologramTick = 0;
    private static boolean hologramRefreshNeeded = true;

    private static final class MysterySession {
        final String crate;
        final RewardChoice prize;
        final int duration;
        final String style;
        final SimpleContainer display;
        final String effectDimension;
        final BlockPos effectPosition;
        int frame;
        int ticks;
        MysterySession(String crate, RewardChoice prize, String effectDimension, BlockPos effectPosition) {
            this.effectDimension = effectDimension;
            this.effectPosition = effectPosition.immutable();
            this.duration = Math.max(20, settingInt(crate, "duration", 100));
            this.style = animationStyle(crate);
            this.display = new SimpleContainer("ROULETTE".equals(style) ? 54 : 27);
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
            loadRarities();
            loadSettings();
            loadHub();
            loadHolograms();
            loadKeys();
            loadStatistics();
            loadMilestones();
            loadTopHolograms();
        } catch (IOException ex) {
            throw new IllegalStateException("AbyssCrate: configuratia nu a putut fi incarcata", ex);
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("crate")
                .then(Commands.literal("menu")
                    .executes(ctx -> { openCrateHub(ctx.getSource().getPlayerOrException()); return 1; })));
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("crate").then(Commands.literal("battle")
                .executes(ctx -> { openBattlePlayers(ctx.getSource().getPlayerOrException(), 0); return 1; })
                .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> { ServerPlayer inviter = ctx.getSource().getPlayerOrException();
                        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                        openBattleCrates(inviter, target.getUUID(), 0); return 1; })))
                .then(Commands.literal("accept").executes(ctx -> acceptBattle(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("deny").executes(ctx -> denyBattle(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("stats")
                    .executes(ctx -> { openBattleStats(ctx.getSource().getPlayerOrException(), ctx.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                        openBattleStats(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player")); return 1;
                    })))
                .then(Commands.literal("top").executes(ctx -> { openBattleTop(ctx.getSource().getPlayerOrException(), 0); return 1; }))));
        });

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
                finishMystery(serverPlayer, new MysterySession(name, selected, player.level().dimension().identifier().toString(), hit.getBlockPos()));
                return InteractionResult.SUCCESS;
            }
            playCrateEffect(serverPlayer, name, "open");
            MysterySession session = new MysterySession(name, selected, player.level().dimension().identifier().toString(), hit.getBlockPos());
            ACTIVE_OPENINGS.put(serverPlayer.getUUID(), session);
            renderMystery(session, false);
            serverPlayer.openMenu(new SimpleMenuProvider((id, inv, viewer) ->
                new ChestMenu(session.display.getContainerSize() == 54 ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x3,
                    id, inv, session.display, session.display.getContainerSize() / 9) {
                    @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                        if (slot >= 0 && slot < session.display.getContainerSize()) return;
                        super.clicked(slot, button, input, clicker);
                    }
                    @Override public ItemStack quickMoveStack(Player clicker, int slot) {
                        return ItemStack.EMPTY;
                    }
                }, Component.literal("AbyssCrate | " + displayName(name))));
            return InteractionResult.SUCCESS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickBattleVisuals(server);
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
                refreshTopHolograms(server);
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
                if (session.ticks <= session.duration && session.ticks % 4 == 0) {
                    tickOpeningEffect(server, session);
                }
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
                .then(Commands.literal("stats")
                    .executes(ctx -> { openStatistics(ctx.getSource().getPlayerOrException(), ctx.getSource().getPlayerOrException().getUUID(), ctx.getSource().getPlayerOrException().getName().getString()); return 1; })
                    .then(Commands.argument("player", EntityArgument.player())
                        .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
                        .executes(ctx -> { ServerPlayer target = EntityArgument.getPlayer(ctx, "player"); openStatistics(ctx.getSource().getPlayerOrException(), target.getUUID(), target.getName().getString()); return 1; })))
                .then(Commands.literal("history")
                    .executes(ctx -> { ServerPlayer player = ctx.getSource().getPlayerOrException(); openHistory(player, player.getUUID(), player.getName().getString(), 0); return 1; })
                    .then(Commands.argument("player", EntityArgument.player())
                        .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
                        .executes(ctx -> { ServerPlayer target = EntityArgument.getPlayer(ctx, "player"); openHistory(ctx.getSource().getPlayerOrException(), target.getUUID(), target.getName().getString(), 0); return 1; })))
                .then(Commands.literal("top")
                    .executes(ctx -> { openLeaderboard(ctx.getSource().getPlayerOrException(), false); return 1; }))
                .then(Commands.literal("hologramtop")
                    .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
                    .then(Commands.literal("set").executes(ctx -> setTopHologram(ctx.getSource().getPlayerOrException(), "top")))
                    .then(Commands.literal("remove").executes(ctx -> removeTopHologram(ctx.getSource().getPlayerOrException(), "top")))
                    .then(Commands.literal("refresh").executes(ctx -> { refreshTopHolograms(ctx.getSource().getServer()); return 1; })))
                .then(Commands.literal("hologramwins")
                    .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
                    .then(Commands.literal("set").executes(ctx -> setTopHologram(ctx.getSource().getPlayerOrException(), "wins")))
                    .then(Commands.literal("remove").executes(ctx -> removeTopHologram(ctx.getSource().getPlayerOrException(), "wins")))
                    .then(Commands.literal("refresh").executes(ctx -> { refreshTopHolograms(ctx.getSource().getServer()); return 1; })))
                .then(Commands.literal("milestones")
                    .executes(ctx -> { openMilestones(ctx.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("milestoneadmin")
                    .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
                    .executes(ctx -> { openMilestoneAdmin(ctx.getSource().getPlayerOrException()); return 1; })
                    .then(Commands.literal("set")
                        .then(Commands.argument("points", IntegerArgumentType.integer(1))
                            .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    int points = IntegerArgumentType.getInteger(ctx, "points");
                                    String command = StringArgumentType.getString(ctx, "command");
                                    MILESTONES.setProperty("reward." + points, command);
                                    saveMilestones();
                                    ctx.getSource().sendSuccess(() -> Component.literal("Milestone " + points + " configurat."), false);
                                    return 1;
                                }))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("points", IntegerArgumentType.integer(1))
                            .executes(ctx -> {
                                int points = IntegerArgumentType.getInteger(ctx, "points");
                                MILESTONES.remove("reward." + points);
                                saveMilestones();
                                ctx.getSource().sendSuccess(() -> Component.literal("Milestone " + points + " eliminat."), false);
                                return 1;
                            })))
                    .then(Commands.literal("reload").executes(ctx -> {
                        try { loadMilestones(); return 1; }
                        catch (IOException ex) { ctx.getSource().sendFailure(Component.literal(ex.getMessage())); return 0; }
                    })))
                .then(Commands.literal("adminstats")
                    .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
                    .executes(ctx -> { openAdminStatistics(ctx.getSource().getPlayerOrException()); return 1; })));
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
            // 3P.1: Custom key design commands; only admins can change key appearance.
            dispatcher.register(Commands.literal("crate")
                .then(Commands.literal("key")
                    .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.literal("name")
                            .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> updateKeyCommand(ctx.getSource(), StringArgumentType.getString(ctx, "name"), "name", StringArgumentType.getString(ctx, "text")))))
                        .then(Commands.literal("material")
                            .then(Commands.argument("item", StringArgumentType.word())
                                .executes(ctx -> updateKeyCommand(ctx.getSource(), StringArgumentType.getString(ctx, "name"), "material", StringArgumentType.getString(ctx, "item")))))
                        .then(Commands.literal("glint")
                            .then(Commands.literal("on").executes(ctx -> updateKeyCommand(ctx.getSource(), StringArgumentType.getString(ctx, "name"), "glint", "true")))
                            .then(Commands.literal("off").executes(ctx -> updateKeyCommand(ctx.getSource(), StringArgumentType.getString(ctx, "name"), "glint", "false"))))
                        .then(Commands.literal("lore")
                            .then(Commands.literal("add")
                                .then(Commands.argument("line", IntegerArgumentType.integer(1, 10))
                                    .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> updateKeyCommand(ctx.getSource(), StringArgumentType.getString(ctx, "name"), "lore." + IntegerArgumentType.getInteger(ctx, "line"), StringArgumentType.getString(ctx, "text"))))))
                            .then(Commands.literal("remove")
                                .then(Commands.argument("line", IntegerArgumentType.integer(1, 10))
                                    .executes(ctx -> updateKeyCommand(ctx.getSource(), StringArgumentType.getString(ctx, "name"), "lore." + IntegerArgumentType.getInteger(ctx, "line"), ""))))))));
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
                        loadRarities();
            loadSettings();
            loadHub();
            loadHolograms();
            loadKeys();
                        loadMilestones();
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

    // 5M.3: Fully configurable hub appearance, loaded at startup and /crate reload.
    private static synchronized void loadHub() throws IOException {
        Files.createDirectories(DIR);
        HUB.clear();
        if (Files.exists(HUB_FILE)) {
            try (InputStream in = Files.newInputStream(HUB_FILE)) { HUB.load(in); }
        } else {
            HUB.setProperty("frame.material", "QUARTZ_BLOCK");
            HUB.setProperty("frame.name", " ");
            HUB.setProperty("frame.enabled", "true");
            HUB.setProperty("menu.title", "AbyssMC | Crate Menu");
            HUB.setProperty("admin.title", "AbyssMC | Admin Panel");
            HUB.setProperty("crates.title", "AbyssMC | Crates");
            HUB.setProperty("admincrates.title", "AbyssMC | Admin Crates");
            HUB.setProperty("menu.10.material", "CHEST");
            HUB.setProperty("menu.10.name", "&6Crates & Rewards");
            HUB.setProperty("menu.10.lore", "&7Vezi crate-urile si recompensele|&eClick pentru lista");
            HUB.setProperty("menu.12.material", "GOLD_INGOT");
            HUB.setProperty("menu.12.name", "&eMilestone Rewards");
            HUB.setProperty("menu.14.material", "DIAMOND_SWORD");
            HUB.setProperty("menu.14.name", "&bCrate Battle");
            HUB.setProperty("menu.16.material", "BOOK");
            HUB.setProperty("menu.16.name", "&aMy Statistics");
            HUB.setProperty("menu.28.material", "CLOCK");
            HUB.setProperty("menu.28.name", "&fReward History");
            HUB.setProperty("menu.30.material", "EMERALD");
            HUB.setProperty("menu.30.name", "&aCrate Leaderboard");
            HUB.setProperty("menu.32.material", "IRON_SWORD");
            HUB.setProperty("menu.32.name", "&bBattle Statistics");
            HUB.setProperty("menu.34.material", "GOLDEN_SWORD");
            HUB.setProperty("menu.34.name", "&6Battle Leaderboard");
            HUB.setProperty("menu.40.material", "EMERALD");
            HUB.setProperty("menu.40.name", "&aAbyssMC");
            HUB.setProperty("menu.40.lore", "&7Bucura-te de crate-uri si battle-uri!");
            HUB.setProperty("menu.admin40.material", "COMMAND_BLOCK");
            HUB.setProperty("menu.admin40.name", "&cAdmin Control Panel");
            HUB.setProperty("menu.admin40.lore", "&7Editoare, setari si administrare|&eClick pentru panoul admin");
            HUB.setProperty("menu.49.material", "BARRIER");
            HUB.setProperty("menu.49.name", "&cClose Menu");
            HUB.setProperty("admin.11.material", "CHEST");
            HUB.setProperty("admin.11.name", "&6Crate Editors");
            HUB.setProperty("admin.13.material", "NETHER_STAR");
            HUB.setProperty("admin.13.name", "&eMilestone Admin");
            HUB.setProperty("admin.15.material", "BOOK");
            HUB.setProperty("admin.15.name", "&bGlobal Statistics");
            HUB.setProperty("admin.31.material", "OAK_SIGN");
            HUB.setProperty("admin.31.name", "&dHolograms & Settings");
            HUB.setProperty("admin.48.material", "ARROW");
            HUB.setProperty("admin.48.name", "&7Back to Main Menu");
            HUB.setProperty("admin.49.material", "BARRIER");
            HUB.setProperty("admin.49.name", "&cClose");
            try (OutputStream out = Files.newOutputStream(HUB_FILE)) {
                HUB.store(out, "AbyssCrate 5M.3 - customize menu and admin buttons. Use /crate reload. Do not change slot numbers; they define button actions.");
            }
        }
    }

    private static String hubValue(String key, String fallback) {
        return HUB.getProperty(key, fallback);
    }

    private static Item hubMaterial(String key, Item fallback) {
        String name = hubValue(key, "").trim().toUpperCase(java.util.Locale.ROOT);
        if (name.startsWith("MINECRAFT:")) name = name.substring(10);
        if (name.isEmpty()) return fallback;
        try { return (Item) Items.class.getField(name).get(null); }
        catch (ReflectiveOperationException | ClassCastException ignored) { return fallback; }
    }

    private static ItemStack configuredHubButton(String key, Item fallbackItem, String fallbackName, String... fallbackLore) {
        Item item = hubMaterial(key + ".material", fallbackItem);
        String name = hubValue(key + ".name", fallbackName);
        String rawLore = hubValue(key + ".lore", String.join("|", fallbackLore));
        return hubButton(item, name, rawLore.isEmpty() ? new String[0] : rawLore.split("\\|", -1));
    }

    // 5M: One central menu; all privileged actions are rechecked on click.
    private static boolean canManageCrates(ServerPlayer player) {
        return player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN);
    }

    private static ItemStack hubButton(Item item, String name, String... description) {
        ItemStack stack = new ItemStack(item);
        // The hub uses the existing formatting parser (legacy &, hex and gradients).
        // Explicitly disable Minecraft's default italic styling for custom names/lore.
        stack.set(DataComponents.CUSTOM_NAME, colored(name).copy().withStyle(style -> style.withItalic(false)));
        List<Component> lines = new ArrayList<>();
        for (String line : description) lines.add(colored(line).copy().withStyle(style -> style.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(lines));
        // Display-only items must not show sword attack damage / speed in the hub.
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
        return stack;
    }

    private static void hubFrame(SimpleContainer view) {
        for (int slot = 0; slot < 54; slot++) {
            int row = slot / 9, col = slot % 9;
            if (Boolean.parseBoolean(hubValue("frame.enabled", "true")) && (row == 0 || row == 5 || col == 0 || col == 8))
                view.setItem(slot, hubButton(hubMaterial("frame.material", Items.QUARTZ_BLOCK), hubValue("frame.name", " ")));
        }
    }

    private static void openCrateHub(ServerPlayer player) {
        boolean admin = canManageCrates(player);
        SimpleContainer view = new SimpleContainer(54);
        hubFrame(view);
        view.setItem(4, configuredHubButton("menu.4", Items.NETHER_STAR, "AbyssMC | Crate Hub", "&7Toate functiile intr-un singur loc", admin ? "&6Mod administrator activ" : "&aMeniu jucator"));
        view.setItem(10, configuredHubButton("menu.10", Items.CHEST, "&6Crates & Rewards", "&7Vezi crate-urile si recompensele", "&eClick pentru lista"));
        view.setItem(12, configuredHubButton("menu.12", Items.GOLD_INGOT, "&eMilestone Rewards", "&7Progresul si recompensele tale", "&eClick pentru deschidere"));
        view.setItem(14, configuredHubButton("menu.14", Items.DIAMOND_SWORD, "&bCrate Battle", "&7Provoaca alti jucatori", "&eClick pentru invitatii"));
        view.setItem(16, configuredHubButton("menu.16", Items.BOOK, "&aMy Statistics", "&7Deschideri, raritati si istoric"));
        view.setItem(28, configuredHubButton("menu.28", Items.CLOCK, "&fReward History", "&7Ultimele recompense castigate"));
        view.setItem(30, configuredHubButton("menu.30", Items.EMERALD, "&aCrate Leaderboard", "&7Top jucatori dupa deschideri"));
        view.setItem(32, configuredHubButton("menu.32", Items.IRON_SWORD, "&bBattle Statistics", "&7Victorii, infrangeri si streak"));
        view.setItem(34, configuredHubButton("menu.34", Items.GOLDEN_SWORD, "&6Battle Leaderboard", "&7Top jucatori dupa victorii"));
        if (admin) {
            view.setItem(40, configuredHubButton("menu.admin40", Items.COMMAND_BLOCK, "&cAdmin Control Panel", "&7Editoare, setari si administrare", "&eClick pentru panoul admin"));
        } else {
            view.setItem(40, configuredHubButton("menu.40", Items.EMERALD, "&aAbyssMC", "&7Bucura-te de crate-uri si battle-uri!"));
        }
        view.setItem(49, configuredHubButton("menu.49", Items.BARRIER, "&cClose Menu", "&7Inchide meniul"));
        showStatsMenu(player, hubValue("menu.title", "AbyssMC | Crate Menu"), view, slot -> {
            switch (slot) {
                case 10 -> openHubCrateList(player, false, 0);
                case 12 -> openMilestones(player);
                case 14 -> openBattlePlayers(player, 0);
                case 16 -> openStatistics(player, player.getUUID(), player.getName().getString());
                case 28 -> openHistory(player, player.getUUID(), player.getName().getString(), 0);
                case 30 -> openLeaderboard(player, false);
                case 32 -> openBattleStats(player, player);
                case 34 -> openBattleTop(player, 0);
                case 40 -> { if (canManageCrates(player)) openHubAdmin(player); }
                case 49 -> player.closeContainer();
                default -> { }
            }
        });
    }

    private static void openHubCrateList(ServerPlayer player, boolean admin, int page) {
        if (admin && !canManageCrates(player)) { openCrateHub(player); return; }
        List<String> crates = battleCrates();
        int maxPage = Math.max(0, (crates.size() - 1) / 28);
        int current = Math.max(0, Math.min(page, maxPage));
        SimpleContainer view = new SimpleContainer(54);
        hubFrame(view);
        view.setItem(4, hubButton(admin ? Items.COMMAND_BLOCK : Items.CHEST,
            admin ? "&cManage Crates" : "&6Browse Crates", admin ? "&7Alege un crate pentru editor" : "&7Alege un crate pentru preview"));
        for (int i = 0; i < 28 && current * 28 + i < crates.size(); i++) {
            String name = crates.get(current * 28 + i);
            int row = i / 7, col = i % 7;
            int slot = 9 + row * 9 + col + 1;
            if (slot >= 45) break;
            view.setItem(slot, hubButton(admin ? Items.COMPARATOR : Items.CHEST, "&e" + displayName(name),
                admin ? "&7Click: editor si setari" : "&7Click: recompense si sanse"));
        }
        view.setItem(45, hubButton(Items.ARROW, "&ePrevious Page"));
        view.setItem(48, hubButton(Items.ARROW, "&7Back"));
        view.setItem(49, hubButton(Items.BARRIER, "&cClose"));
        view.setItem(53, hubButton(Items.ARROW, "&eNext Page"));
        showStatsMenu(player, admin ? hubValue("admincrates.title", "AbyssMC | Admin Crates") : hubValue("crates.title", "AbyssMC | Crates"), view, slot -> {
            if (slot >= 10 && slot < 45 && slot % 9 >= 1 && slot % 9 <= 7) {
                int index = current * 28 + ((slot / 9) - 1) * 7 + (slot % 9) - 1;
                if (index >= 0 && index < crates.size()) {
                    String crate = crates.get(index);
                    if (admin) { if (canManageCrates(player)) openMainEditor(player, crate); }
                    else openPlayerPreview(player, crate);
                }
            } else if (slot == 45) openHubCrateList(player, admin, current - 1);
            else if (slot == 48) { if (admin) openHubAdmin(player); else openCrateHub(player); }
            else if (slot == 49) player.closeContainer();
            else if (slot == 53) openHubCrateList(player, admin, current + 1);
        });
    }

    private static void openHubAdmin(ServerPlayer player) {
        if (!canManageCrates(player)) { openCrateHub(player); return; }
        SimpleContainer view = new SimpleContainer(54);
        hubFrame(view);
        view.setItem(4, configuredHubButton("admin.4", Items.COMMAND_BLOCK, "&cAbyssMC | Admin Panel", "&7Acces restrictionat la administratori"));
        view.setItem(11, configuredHubButton("admin.11", Items.CHEST, "&6Crate Editors", "&7Recompense, chei, raritati si animatii"));
        view.setItem(13, configuredHubButton("admin.13", Items.NETHER_STAR, "&eMilestone Admin", "&7Vizualizeaza recompensele configurate"));
        view.setItem(15, configuredHubButton("admin.15", Items.BOOK, "&bGlobal Statistics", "&7Total deschideri si statistici globale"));
        view.setItem(31, configuredHubButton("admin.31", Items.OAK_SIGN, "&dHolograms & Settings", "&7Alege un crate din editor", "&7si deschide setarile / hologramele"));
        view.setItem(48, configuredHubButton("admin.48", Items.ARROW, "&7Back to Main Menu"));
        view.setItem(49, configuredHubButton("admin.49", Items.BARRIER, "&cClose"));
        showStatsMenu(player, hubValue("admin.title", "AbyssMC | Admin Panel"), view, slot -> {
            if (!canManageCrates(player)) { player.closeContainer(); return; }
            switch (slot) {
                case 11, 31 -> openHubCrateList(player, true, 0);
                case 13 -> openMilestoneAdmin(player);
                case 15 -> openAdminStatistics(player);
                case 48 -> openCrateHub(player);
                case 49 -> player.closeContainer();
                default -> { }
            }
        });
    }

    // 3F: all editor menus are server-side and block moving the display buttons.
    private static ItemStack button(Item item, String label) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        return stack;
    }

    private static ItemStack previewButton(Item item, Component label) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, label);
        return stack;
    }

    private static void openMainEditor(ServerPlayer admin, String crate) {
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(10, button(Items.EMERALD, "Add Item"));
        view.setItem(12, button(Items.CHEST, "View Rewards"));
        view.setItem(14, button(Items.COMPARATOR, "Change Chance - select reward"));
        view.setItem(16, button(Items.COMPARATOR, "Crate Settings"));
        view.setItem(20, button(Items.TRIPWIRE_HOOK, "Custom Key Editor"));
        view.setItem(24, button(Items.NETHER_STAR, "Milestone Rewards"));
        view.setItem(22, button(Items.BARRIER, "Close"));
        admin.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput ContainerInput, Player player) {
                if (slot >= 0 && slot < 27) {
                    if (slot == 10) openAddEditor(admin, crate);
                    else if (slot == 12 || slot == 14) openRewardsEditor(admin, crate, 0);
                    else if (slot == 16) openCrateSettings(admin, crate);
                    else if (slot == 20) openKeyEditor(admin, crate);
                    else if (slot == 24) openMilestoneAdmin(admin);
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
        view.setItem(10, button(Items.NAME_TAG, "Line 1: /crate hologram " + crate + " add 1 <text>"));
        view.setItem(11, button(Items.PAPER, "Line 2: /crate hologram " + crate + " add 2 <text>"));
        view.setItem(12, button(Items.PAPER, "Lines 1-10: /crate hologram " + crate + " add <1-10> <text>"));
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
        // 3R.5.1: Support legacy & colors, HEX, formatting and Birdflop gradients in Text Displays.
        // Build a structured SNBT component rather than displaying formatting tokens literally.
        Matcher gradient = Pattern.compile("(?i)<gradient:#([0-9a-f]{6}):#([0-9a-f]{6})>(.*?)</gradient>", Pattern.DOTALL).matcher(text);
        StringBuffer expanded = new StringBuffer();
        while (gradient.find()) {
            String body = gradient.group(3);
            int from = Integer.parseInt(gradient.group(1), 16);
            int to = Integer.parseInt(gradient.group(2), 16);
            int count = body.codePointCount(0, body.length());
            StringBuilder coloredBody = new StringBuilder();
            int i = 0;
            for (int off = 0; off < body.length();) {
                int cp = body.codePointAt(off);
                double t = count <= 1 ? 0 : (double)i / (count - 1);
                int r = (int)Math.round(((from >> 16) & 255) * (1-t) + ((to >> 16) & 255) * t);
                int g = (int)Math.round(((from >> 8) & 255) * (1-t) + ((to >> 8) & 255) * t);
                int bl = (int)Math.round((from & 255) * (1-t) + (to & 255) * t);
                coloredBody.append(String.format(Locale.ROOT, "&#%02X%02X%02X", r, g, bl));
                coloredBody.appendCodePoint(cp);
                off += Character.charCount(cp);
                i++;
            }
            gradient.appendReplacement(expanded, Matcher.quoteReplacement(coloredBody.toString()));
        }
        gradient.appendTail(expanded);
        text = expanded.toString();

        List<String> segments = new ArrayList<>();
        Matcher token = Pattern.compile("(?i)&#([0-9a-f]{6})|&([0-9a-fk-or])").matcher(text);
        String color = fallbackColor;
        boolean bold = false, italic = false, underlined = false, strikethrough = false, obfuscated = false;
        int start = 0;
        while (token.find()) {
            if (token.start() > start) {
                segments.add(hologramSegment(text.substring(start, token.start()), color, bold, italic, underlined, strikethrough, obfuscated));
            }
            if (token.group(1) != null) {
                color = "#" + token.group(1).toUpperCase(Locale.ROOT);
                bold = italic = underlined = strikethrough = obfuscated = false;
            } else {
                char code = Character.toLowerCase(token.group(2).charAt(0));
                switch (code) {
                    case 'l' -> bold = true;
                    case 'o' -> italic = true;
                    case 'n' -> underlined = true;
                    case 'm' -> strikethrough = true;
                    case 'k' -> obfuscated = true;
                    case 'r' -> { color = fallbackColor; bold = italic = underlined = strikethrough = obfuscated = false; }
                    default -> {
                        color = switch (code) {
                            case '0' -> "#000000"; case '1' -> "#0000AA"; case '2' -> "#00AA00";
                            case '3' -> "#00AAAA"; case '4' -> "#AA0000"; case '5' -> "#AA00AA";
                            case '6' -> "#FFAA00"; case '7' -> "#AAAAAA"; case '8' -> "#555555";
                            case '9' -> "#5555FF"; case 'a' -> "#55FF55"; case 'b' -> "#55FFFF";
                            case 'c' -> "#FF5555"; case 'd' -> "#FF55FF"; case 'e' -> "#FFFF55";
                            case 'f' -> "#FFFFFF"; default -> fallbackColor;
                        };
                        bold = italic = underlined = strikethrough = obfuscated = false;
                    }
                }
            }
            start = token.end();
        }
        if (start < text.length()) {
            segments.add(hologramSegment(text.substring(start), color, bold, italic, underlined, strikethrough, obfuscated));
        }
        if (segments.isEmpty()) return "{text:\"\"}";
        if (segments.size() == 1) return segments.get(0);
        return "{text:\"\",extra:[" + String.join(",", segments) + "]}";
    }

    private static String hologramSegment(String value, String color, boolean bold, boolean italic,
                                          boolean underlined, boolean strikethrough, boolean obfuscated) {
        return "{text:" + snbtQuote(value) + ",color:" + snbtQuote(color)
                + ",bold:" + bold + ",italic:" + italic + ",underlined:" + underlined
                + ",strikethrough:" + strikethrough + ",obfuscated:" + obfuscated + "}";
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

    private static String previewRaw(String key) {
        synchronized (MESSAGES) { return MESSAGES.getProperty(key, key); }
    }

    private static Component previewText(String key, String... replacements) {
        String value = previewRaw(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }
        return colored(value);
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
            icon.set(DataComponents.CUSTOM_NAME, previewText("preview.reward-name", "{id}", Integer.toString(rewardId)));
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                previewText("preview.chance", "{chance}", String.format(Locale.ROOT, "%.2f", percent)),
                previewText("preview.amount", "{amount}", Integer.toString(amount)),
                previewText("preview.readonly")
            )));
            view.setItem(i, icon);
        }
        if (current > 0) view.setItem(45, previewButton(Items.ARROW, previewText("preview.previous")));
        view.setItem(49, previewButton(Items.BOOK, previewText("preview.page", "{page}", Integer.toString(current + 1), "{total}", Integer.toString(maxPage + 1), "{rewards}", Integer.toString(ids.size()))));
        if (current < maxPage) view.setItem(53, previewButton(Items.ARROW, previewText("preview.next")));
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
        }, previewText("preview.title", "{crate}", displayName(crate), "{page}", Integer.toString(current + 1), "{total}", Integer.toString(maxPage + 1))));
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
        view.setItem(23, button(Items.CLOCK, "Animation Style: " + animationStyle(crate) + " (click to cycle)"));
        view.setItem(24, button(Items.TOTEM_OF_UNDYING, "Rare Reward Effects: " + (settingBool(crate, "rare-effects", true) ? "ON" : "OFF")));
        view.setItem(17, button(Items.ENDER_EYE, "Opening Effect: " + openingEffect(crate) + " (click to cycle)"));
        view.setItem(18, button(Items.FIREWORK_ROCKET, "Final Burst: " + (settingBool(crate, "final-burst", true) ? "ON" : "OFF")));
        view.setItem(25, button(Items.GLOWSTONE_DUST, "Effect Intensity: " + openingIntensity(crate) + " (click to cycle)"));
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
                        case 24 -> changeSetting(admin, crate, "rare-effects", Boolean.toString(!settingBool(crate, "rare-effects", true)));
                        case 17 -> changeSetting(admin, crate, "opening-effect", switch (openingEffect(crate)) {
                            case "OFF" -> "MAGIC_CIRCLE";
                            case "MAGIC_CIRCLE" -> "ENERGY_BEAM";
                            case "ENERGY_BEAM" -> "SPIRAL_VORTEX";
                            default -> "OFF";
                        });
                        case 18 -> changeSetting(admin, crate, "final-burst", Boolean.toString(!settingBool(crate, "final-burst", true)));
                        case 25 -> changeSetting(admin, crate, "effect-intensity", switch (openingIntensity(crate)) {
                            case "LOW" -> "MEDIUM";
                            case "MEDIUM" -> "HIGH";
                            default -> "LOW";
                        });
                        case 23 -> changeSetting(admin, crate, "animation_style", switch (animationStyle(crate)) {
                            case "CLASSIC" -> "ROULETTE";
                            case "ROULETTE" -> "EPIC";
                            default -> "CLASSIC";
                        });
                        default -> { }
                    }
                    return;
                }
                super.clicked(slot, button, input, clicker);
            }
        }, Component.literal("AbyssCrate Settings | " + crate)));
    }


    // 3Q.3: Server-side opening effects at the actual crate block (not the player's position).
    // No entities, block changes or client-side resource packs required.
    private static String openingEffect(String crate) {
        String value = setting(crate, "opening-effect", "OFF").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "MAGIC_CIRCLE", "ENERGY_BEAM", "SPIRAL_VORTEX" -> value;
            default -> "OFF";
        };
    }

    private static String openingIntensity(String crate) {
        String value = setting(crate, "effect-intensity", "MEDIUM").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "LOW", "HIGH" -> value;
            default -> "MEDIUM";
        };
    }

    private static int openingParticleBudget(String crate) {
        return switch (openingIntensity(crate)) {
            case "LOW" -> 8;
            case "HIGH" -> 24;
            default -> 14;
        };
    }

    private static void particleAt(net.minecraft.server.MinecraftServer server, String dimension,
                                   double x, double y, double z, String particle, int count) {
        // Coordinates/dimension come from a server-validated block interaction.
        if (!dimension.matches("[a-z0-9_:.\\/-]+")) return;
        String position = String.format(Locale.ROOT, "%.3f %.3f %.3f", x, y, z);
        runHologramCommand(server, "execute in " + dimension + " positioned " + position
            + " if entity @a[distance=..32] run particle " + particle + " ~ ~ ~ 0 0 0 0 " + count + " normal");
    }

    private static void tickOpeningEffect(net.minecraft.server.MinecraftServer server, MysterySession session) {
        if (!settingBool(session.crate, "effects", true)) return;
        String style = openingEffect(session.crate);
        if ("OFF".equals(style)) return;
        int budget = openingParticleBudget(session.crate);
        double cx = session.effectPosition.getX() + 0.5;
        double cy = session.effectPosition.getY();
        double cz = session.effectPosition.getZ() + 0.5;
        String particle = effectParticle(session.crate);
        double phase = session.ticks * 0.09;
        for (int i = 0; i < budget; i++) {
            double angle = (Math.PI * 2 * i / budget) + phase;
            double x, y, z;
            switch (style) {
                case "ENERGY_BEAM" -> {
                    x = cx + Math.cos(angle) * 0.13;
                    y = cy + 0.3 + (2.7 * i / budget);
                    z = cz + Math.sin(angle) * 0.13;
                }
                case "SPIRAL_VORTEX" -> {
                    double height = 0.15 + 2.4 * i / budget;
                    double spiral = angle * 1.8 + phase;
                    x = cx + Math.cos(spiral) * 0.75;
                    y = cy + height;
                    z = cz + Math.sin(spiral) * 0.75;
                }
                default -> { // MAGIC_CIRCLE
                    x = cx + Math.cos(angle) * 1.05;
                    y = cy + 0.25 + 0.12 * Math.sin(angle * 2 + phase);
                    z = cz + Math.sin(angle) * 1.05;
                }
            }
            particleAt(server, session.effectDimension, x, y, z, particle, 1);
        }
    }

    private static void finalOpeningBurst(ServerPlayer player, MysterySession session, String rarity) {
        if (!settingBool(session.crate, "effects", true) || !settingBool(session.crate, "final-burst", true)) return;
        var server = player.level().getServer();
        if (server == null) return;
        String particle = switch (rarity) {
            case "RARE" -> "minecraft:happy_villager";
            case "EPIC" -> "minecraft:witch";
            case "LEGENDARY" -> "minecraft:end_rod";
            default -> "minecraft:enchant";
        };
        int budget = openingParticleBudget(session.crate);
        double cx = session.effectPosition.getX() + 0.5;
        double cy = session.effectPosition.getY() + 1.0;
        double cz = session.effectPosition.getZ() + 0.5;
        for (int i = 0; i < budget; i++) {
            double angle = 2 * Math.PI * i / budget;
            double radius = 0.45 + 0.55 * (i % 3) / 2.0;
            particleAt(server, session.effectDimension,
                cx + Math.cos(angle) * radius, cy + 0.5 * Math.sin(angle * 2),
                cz + Math.sin(angle) * radius, particle, 2);
        }
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

    private static String rarityMessage(String template, ServerPlayer player, String crate, String item, int count, String rarity) {
        return template.replace("{prefix}", MESSAGES.getProperty("prefix", DEFAULT_GRADIENT_PREFIX))
            .replace("{player}", player.getName().getString())
            .replace("{crate}", crate).replace("{item}", item)
            .replace("{amount}", Integer.toString(count)).replace("{rarity}", rarity);
    }

    private static void playRarityEffect(ServerPlayer player, String crate, String rarity) {
        var server = player.level().getServer();
        if (server == null) return;
        String name = player.getGameProfile().name();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) return;
        String particle = switch (rarity) {
            case "RARE" -> "minecraft:happy_villager";
            case "EPIC" -> "minecraft:witch";
            case "LEGENDARY" -> "minecraft:end_rod";
            default -> "minecraft:enchant";
        };
        String sound = switch (rarity) {
            case "RARE" -> "minecraft:block.amethyst_block.chime";
            case "EPIC" -> "minecraft:entity.evoker.cast_spell";
            case "LEGENDARY" -> "minecraft:ui.toast.challenge_complete";
            default -> "minecraft:entity.experience_orb.pickup";
        };
        if (settingBool(crate, "effects", true)) runHologramCommand(server, "execute as " + name + " at @s run particle " + particle + " ~ ~1 ~ 0.5 0.65 0.5 0.08 " + ("LEGENDARY".equals(rarity) ? 80 : 24) + " force");
        if (settingBool(crate, "sounds", true)) runHologramCommand(server, "execute as " + name + " at @s run playsound " + sound + " master @s ~ ~ ~ 0.9 1");
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

    // 3Q.2: Rarity is separate from reward weights, so existing rewards stay compatible.
    private static synchronized void loadRarities() throws IOException {
        Files.createDirectories(DIR);
        RARITIES.clear();
        if (Files.exists(RARITIES_FILE)) try (InputStream in = Files.newInputStream(RARITIES_FILE)) { RARITIES.load(in); }
    }

    private static synchronized void saveRarities() throws IOException {
        Files.createDirectories(DIR);
        try (OutputStream out = Files.newOutputStream(RARITIES_FILE)) {
            RARITIES.store(out, "AbyssCrate 3Q.2 manual reward rarities: COMMON, RARE, EPIC, LEGENDARY");
        }
    }

    private static String rewardRarity(String crate, int id) {
        synchronized (RARITIES) {
            String value = RARITIES.getProperty(crate + "." + id, "COMMON").toUpperCase(Locale.ROOT);
            return switch (value) {
                case "RARE", "EPIC", "LEGENDARY" -> value;
                default -> "COMMON";
            };
        }
    }

    private static String nextRarity(String current) {
        return switch (current) {
            case "COMMON" -> "RARE";
            case "RARE" -> "EPIC";
            case "EPIC" -> "LEGENDARY";
            default -> "COMMON";
        };
    }

    private static void changeRewardRarity(ServerPlayer admin, String crate, int id, int page) {
        synchronized (RARITIES) {
            String key = crate + "." + id;
            String previous = RARITIES.getProperty(key);
            RARITIES.setProperty(key, nextRarity(rewardRarity(crate, id)));
            try { saveRarities(); }
            catch (IOException ex) {
                if (previous == null) RARITIES.remove(key); else RARITIES.setProperty(key, previous);
                admin.sendSystemMessage(Component.literal("[AbyssCrate] Rarity save failed"));
            }
        }
        openRewardDetails(admin, crate, id, page);
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
        view.setItem(22, button(Items.NETHER_STAR, "Reward Rarity: " + rewardRarity(crate, id) + " (click to cycle)"));
        view.setItem(23, button(Items.ARROW, "Back"));
        admin.openMenu(new SimpleMenuProvider((sync, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, sync, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput ContainerInput, Player player) {
                if (slot >= 0 && slot < 27) {
                    if (slot == 23) { openRewardsEditor(admin, crate, page); return; }
                    if (slot == 22) { changeRewardRarity(admin, crate, id, page); return; }
                    int delta = slot == 10 ? -10 : slot == 11 ? -1 : slot == 15 ? 1 : slot == 16 ? 10 : 0;
                    if (slot == 21 || delta != 0) {
                        synchronized (REWARDS) {
                            String existing = REWARDS.getProperty(key);
                            if (existing != null) {
                                if (slot == 21) {
                                    REWARDS.remove(key);
                                    synchronized (RARITIES) {
                                        String oldRarity = (String) RARITIES.remove(key);
                                        try { saveRarities(); }
                                        catch (IOException ex) {
                                            if (oldRarity != null) RARITIES.setProperty(key, oldRarity);
                                            admin.sendSystemMessage(Component.literal("[AbyssCrate] Rarity cleanup save failed"));
                                        }
                                    }
                                }
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

    // 3Q.1: Style is snapshotted at opening; changing settings never alters an active session.
    private static String animationStyle(String crate) {
        String value = setting(crate, "animation_style", "CLASSIC").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "ROULETTE", "EPIC" -> value;
            default -> "CLASSIC";
        };
    }

    private static ItemStack rewardIcon(RewardChoice reward) {
        return reward == null ? ItemStack.EMPTY : new ItemStack(reward.item(), reward.count());
    }

    private static void renderMystery(MysterySession session, boolean reveal) {
        for (int slot = 0; slot < session.display.getContainerSize(); slot++)
            session.display.setItem(slot, ItemStack.EMPTY);
        session.frame++;
        switch (session.style) {
            case "ROULETTE" -> {
                // The center (slot 22) is the winner indicator. Visual rolls never select the prize.
                for (int slot = 18; slot <= 26; slot++) {
                    RewardChoice candidate = reveal && slot == 22 ? session.prize : chooseReward(session.crate);
                    session.display.setItem(slot, rewardIcon(candidate));
                }
                session.display.setItem(4, button(Items.NETHER_STAR, "ROULETTE | " + (reveal ? "WINNER!" : "Rolling...")));
                session.display.setItem(13, button(Items.ARROW, "\u25bc WINNER \u25bc"));
                session.display.setItem(31, button(Items.ARROW, "\u25b2 WINNER \u25b2"));
                session.display.setItem(49, button(Items.EMERALD, reveal ? "Recompensa castigatoare!" : "Se incetineste..."));
            }
            case "EPIC" -> {
                session.display.setItem(4, button(Items.NETHER_STAR, reveal ? "EPIC REVEAL | WINNER!" : "EPIC REVEAL"));
                if (reveal) {
                    session.display.setItem(13, rewardIcon(session.prize));
                    session.display.setItem(22, button(Items.EMERALD, "Felicitari!"));
                } else {
                    int remaining = Math.max(1, (session.duration - session.ticks + 19) / 20);
                    session.display.setItem(13, button(Items.ENDER_CHEST, "Recompensa misterioasa"));
                    session.display.setItem(22, button(Items.CLOCK, "Dezvaluire in " + remaining + "s"));
                    for (int slot : new int[]{10, 12, 14, 16})
                        session.display.setItem(slot, button(Items.AMETHYST_SHARD, "???"));
                }
            }
            default -> {
                // Preserve the original Classic Spin layout and timing.
                session.display.setItem(4, button(Items.NETHER_STAR, reveal ? "Recompensa castigatoare!" : "AbyssCrate - Se deschide..."));
                for (int slot = 9; slot < 18; slot++) {
                    RewardChoice candidate = reveal && slot == 13 ? session.prize : chooseReward(session.crate);
                    session.display.setItem(slot, rewardIcon(candidate));
                }
                session.display.setItem(22, button(reveal ? Items.EMERALD : Items.CLOCK,
                    reveal ? "Felicitari!" : "Asteapta rezultatul..."));
            }
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
        recordOpening(player, session.crate, session.prize, displayName);
        awardMilestones(player);
        playCrateEffect(player, session.crate, "win");
        String rarity = rewardRarity(session.crate, session.prize.id());
        finalOpeningBurst(player, session, rarity);
        if (settingBool(session.crate, "rare-effects", true)) {
            playRarityEffect(player, session.crate, rarity);
        }
        String winText = message("reward-won", session.crate)
            .replace("{item}", displayName)
            .replace("{amount}", Integer.toString(session.prize.count()));
        if (settingBool(session.crate, "rare-effects", true) && "LEGENDARY".equals(rarity)) {
            String template = MESSAGES.getProperty("rarity.legendary-broadcast", "{prefix}&#FFD700{player} a castigat o recompensa LEGENDARY: {amount} x {item} din {crate}!");
            String announcement = rarityMessage(template, player, session.crate, displayName, session.prize.count(), rarity);
            for (ServerPlayer online : player.level().getServer().getPlayerList().getPlayers()) online.sendSystemMessage(colored(announcement));
        } else if (settingBool(session.crate, "broadcast", false)) {
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
        if (settingBool(session.crate, "rare-effects", true)) {
            String defaultMessage = switch (rarity) {
                case "RARE" -> "{prefix}&#55FF55Recompensa RARE obtinuta!";
                case "EPIC" -> "{prefix}&#AA55FFRecompensa EPIC obtinuta!";
                case "LEGENDARY" -> "{prefix}&#FFD700Recompensa LEGENDARY obtinuta!";
                default -> "";
            };
            String template = MESSAGES.getProperty("rarity." + rarity.toLowerCase(Locale.ROOT) + "-win", defaultMessage);
            if (!template.isEmpty()) player.sendSystemMessage(colored(rarityMessage(template, player, session.crate, displayName, session.prize.count(), rarity)));
        }
    }

    // 3R.5: Holograms are rebuilt at most every 30 seconds, reusing the 3L Text Display approach.
    private static synchronized void loadTopHolograms() throws IOException {
        TOP_HOLOGRAMS.clear();
        if (Files.exists(TOP_HOLOGRAMS_FILE)) try (InputStream in = Files.newInputStream(TOP_HOLOGRAMS_FILE)) {
            TOP_HOLOGRAMS.load(in);
        }
    }

    private static synchronized void saveTopHolograms() throws IOException {
        Files.createDirectories(TOP_HOLOGRAMS_FILE.getParent());
        try (OutputStream out = Files.newOutputStream(TOP_HOLOGRAMS_FILE)) {
            TOP_HOLOGRAMS.store(out, "AbyssCrate 3R.5 live leaderboard hologram positions");
        }
    }

    private static int setTopHologram(ServerPlayer player, String kind) {
        clearTopHologram(player.level().getServer(), kind);
        BlockPos pos = player.blockPosition();
        String dimension = player.level().dimension().identifier().toString();
        synchronized (TOP_HOLOGRAMS) {
            TOP_HOLOGRAMS.setProperty(kind, dimension + "|" + (pos.getX() + 0.5) + "|" + (pos.getY() + 2.8) + "|" + (pos.getZ() + 0.5));
            try { saveTopHolograms(); } catch (IOException ex) {
                player.sendSystemMessage(Component.literal("[AbyssCrate] Nu am putut salva pozitia hologramei: " + ex.getMessage()));
                return 0;
            }
        }
        refreshTopHolograms(player.level().getServer());
        player.sendSystemMessage(Component.literal("[AbyssCrate] Holograma " + kind + " a fost setata."));
        return 1;
    }

    private static int removeTopHologram(ServerPlayer player, String kind) {
        clearTopHologram(player.level().getServer(), kind);
        synchronized (TOP_HOLOGRAMS) {
            TOP_HOLOGRAMS.remove(kind);
            try { saveTopHolograms(); } catch (IOException ex) {
                player.sendSystemMessage(Component.literal("[AbyssCrate] Nu am putut salva stergerea: " + ex.getMessage()));
                return 0;
            }
        }
        player.sendSystemMessage(Component.literal("[AbyssCrate] Holograma " + kind + " a fost stearsa."));
        return 1;
    }

    private static void clearTopHologram(net.minecraft.server.MinecraftServer server, String kind) {
        String saved;
        synchronized (TOP_HOLOGRAMS) { saved = TOP_HOLOGRAMS.getProperty(kind); }
        if (saved == null) return;
        String[] parts = saved.split("\\|", 4);
        if (parts.length != 4 || !parts[0].matches("[a-z0-9_:.\\/-]+")) return;
        try {
            double x = Double.parseDouble(parts[1]), y = Double.parseDouble(parts[2]), z = Double.parseDouble(parts[3]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
            runHologramCommand(server, "execute in " + parts[0] + " positioned " + x + " " + y + " " + z
                    + " run kill @e[type=minecraft:text_display,tag=abysscrate_top_" + kind + ",distance=..4]");
        } catch (NumberFormatException ignored) { }
    }

    private static void refreshTopHolograms(net.minecraft.server.MinecraftServer server) {
        for (String kind : new String[] {"top", "wins"}) {
            String saved;
            synchronized (TOP_HOLOGRAMS) { saved = TOP_HOLOGRAMS.getProperty(kind); }
            if (saved == null) continue;
            String[] parts = saved.split("\\|", 4);
            if (parts.length != 4 || !parts[0].matches("[a-z0-9_:.\\/-]+")) continue;
            try {
                double x = Double.parseDouble(parts[1]), y = Double.parseDouble(parts[2]), z = Double.parseDouble(parts[3]);
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) continue;
                clearTopHologram(server, kind);
                boolean wins = kind.equals("wins");
                String field = wins ? "rarity.LEGENDARY" : "total";
                List<String> ids = leaderboardIds(field);
                String title = MESSAGES.getProperty("hologram-" + kind + "-title", wins ? "&6&l✦ TOP LEGENDARY WINS ✦" : "&b&l✦ TOP CRATE OPENS ✦");
                // 3R.6: 10 optional lines below each live leaderboard.
                // Defaults are only shown for the first three footer lines.
                List<String> footerLines = new ArrayList<>();
                for (int footer = 1; footer <= 10; footer++) {
                    String defaultFooter = switch (footer) {
                        case 1 -> wins ? "&6✦ Vrei sa castigi LEGENDARY? ✦" : "&a✦ Vrei sa ajungi in TOP 10? ✦";
                        case 2 -> "&fVoteaza pe serverul AbyssMC:";
                        case 3 -> "&#55FFFFabyss-mc.ro/vote";
                        default -> "";
                    };
                    String footerText = MESSAGES.getProperty("hologram-" + kind + "-footer-" + footer, defaultFooter);
                    if (footerText != null && !footerText.isBlank()) footerLines.add(footerText);
                }
                for (int line = 0; line <= 10 + footerLines.size(); line++) {
                    String value;
                    if (line == 0) value = title;
                    else if (line > 10) {
                        value = footerLines.get(line - 11);
                    } else {
                        int rank = line - 1;
                        String name = "Unknown";
                        long count = 0;
                        if (rank < ids.size()) {
                            String id = ids.get(rank);
                            name = STATS.getProperty("player." + id + ".name", "Unknown");
                            count = stat("player." + id + "." + field);
                        }
                        String format = MESSAGES.getProperty("hologram-" + kind + "-line", "&e#{rank} &f{name} &7- &b{count}");
                        value = format.replace("{rank}", Integer.toString(line)).replace("{name}", name).replace("{count}", Long.toString(count));
                    }
                    double lineY = y - line * 0.28;
                    String nbt = "{Tags:[\"abysscrate_top_" + kind + "\"],billboard:\"center\",alignment:\"center\",see_through:1b,background:0,shadow:1b,"
                            + "transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[0.5f,0.5f,0.5f],right_rotation:[0f,0f,0f,1f]},"
                            + "text:" + hologramComponent(value, line == 0 ? "#FFD700" : "#FFFFFF") + "}";
                    runHologramCommand(server, "execute in " + parts[0] + " positioned " + x + " " + lineY + " " + z
                            + " run summon minecraft:text_display ~ ~ ~ " + nbt);
                }
            } catch (RuntimeException ex) {
                System.err.println("[AbyssCrate] Live top hologram refresh failed: " + ex.getMessage());
            }
        }
    }

    // 3R: Only completed, delivered rewards are recorded. The caller is finishMystery.
    private static synchronized void loadStatistics() throws IOException {
        Files.createDirectories(STATS_DIR);
        if (Files.exists(STATS_FILE)) try (InputStream in = Files.newInputStream(STATS_FILE)) { STATS.load(in); }
        if (Files.exists(HISTORY_FILE)) try (InputStream in = Files.newInputStream(HISTORY_FILE)) { HISTORY.load(in); }
    }

    private static synchronized void persistStatistics() throws IOException {
        Files.createDirectories(STATS_DIR);
        try (OutputStream out = Files.newOutputStream(STATS_FILE)) { STATS.store(out, "AbyssCrate 3R statistics by UUID"); }
        try (OutputStream out = Files.newOutputStream(HISTORY_FILE)) { HISTORY.store(out, "AbyssCrate 3R bounded reward history"); }
    }

    private static long stat(String key) {
        try { return Long.parseLong(STATS.getProperty(key, "0")); } catch (NumberFormatException ex) { return 0; }
    }

    private static void incrementStat(String key) { STATS.setProperty(key, Long.toString(stat(key) + 1)); }

    private static synchronized void recordOpening(ServerPlayer player, String crate, RewardChoice prize, String displayName) {
        String id = player.getUUID().toString();
        String rarity = rewardRarity(crate, prize.id());
        STATS.setProperty("player." + id + ".name", player.getName().getString());
        incrementStat("player." + id + ".total");
        incrementStat("player." + id + ".crate." + crate);
        incrementStat("player." + id + ".rarity." + rarity);
        incrementStat("global.total");
        incrementStat("global.crate." + crate);
        incrementStat("global.rarity." + rarity);
        int size;
        try { size = Integer.parseInt(HISTORY.getProperty(id + ".count", "0")); } catch (NumberFormatException ex) { size = 0; }
        for (int i = Math.min(size, MAX_HISTORY - 1); i >= 1; i--)
            HISTORY.setProperty(id + "." + i, HISTORY.getProperty(id + "." + (i - 1), ""));
        // Tabs/newlines are removed to keep each record on one line in the GUI.
        String safeName = displayName.replace('\t', ' ').replace('\n', ' ').replace('|', '/');
        HISTORY.setProperty(id + ".0", System.currentTimeMillis() + "|" + crate + "|" + rarity + "|" + prize.count() + "|" + safeName);
        HISTORY.setProperty(id + ".count", Integer.toString(Math.min(MAX_HISTORY, size + 1)));
        try { persistStatistics(); } catch (IOException ex) { System.err.println("[AbyssCrate] Cannot save statistics: " + ex.getMessage()); }
    }

    // 3S.1: Existing player.total statistics are the single source of truth.
    // Every crate type contributes exactly one point; rewards do not consume points.
    private static synchronized void loadMilestones() throws IOException {
        Files.createDirectories(DIR);
        if (!Files.exists(MILESTONES_FILE)) {
            MILESTONES.clear();
            MILESTONES.setProperty("enabled", "true");
            MILESTONES.setProperty("max-level", "200");
            MILESTONES.setProperty("reward.30", "give {player} minecraft:emerald 3");
            MILESTONES.setProperty("reward.50", "give {player} minecraft:diamond 3");
            MILESTONES.setProperty("reward.70", "give {player} minecraft:gold_ingot 16");
            MILESTONES.setProperty("reward.100", "give {player} minecraft:nether_star 1");
            MILESTONES.setProperty("reward.130", "give {player} minecraft:diamond 8");
            MILESTONES.setProperty("reward.150", "give {player} minecraft:emerald 32");
            MILESTONES.setProperty("reward.200", "give {player} minecraft:netherite_ingot 1");
            saveMilestones();
        }
        MILESTONES.clear();
        try (InputStream in = Files.newInputStream(MILESTONES_FILE)) { MILESTONES.load(in); }
    }

    private static synchronized void saveMilestones() {
        try {
            Files.createDirectories(DIR);
            try (OutputStream out = Files.newOutputStream(MILESTONES_FILE)) {
                MILESTONES.store(out, "AbyssCrate 3S.1 milestones: reward.<points>=console command with {player}");
            }
        } catch (IOException ex) {
            System.err.println("[AbyssCrate] Cannot save milestones: " + ex.getMessage());
        }
    }

    private static List<Integer> milestoneThresholds() {
        List<Integer> thresholds = new ArrayList<>();
        for (String key : MILESTONES.stringPropertyNames()) {
            if (!key.startsWith("reward.")) continue;
            try {
                int n = Integer.parseInt(key.substring(7));
                if (n > 0) thresholds.add(n);
            } catch (NumberFormatException ignored) {}
        }
        thresholds.sort(Integer::compareTo);
        return thresholds;
    }

    private static synchronized void awardMilestones(ServerPlayer player) {
        if (!Boolean.parseBoolean(MILESTONES.getProperty("enabled", "true"))) return;
        String id = player.getUUID().toString();
        long points = stat("player." + id + ".total");
        for (int threshold : milestoneThresholds()) {
            if (points < threshold) continue;
            String claimed = "milestone." + threshold;
            if ("true".equals(STATS.getProperty("player." + id + "." + claimed))) continue;
            String command = MILESTONES.getProperty("reward." + threshold, "").trim();
            if (command.isEmpty()) continue;
            // Persist claim before running reward command to avoid duplicate rewards on retries.
            STATS.setProperty("player." + id + "." + claimed, "true");
            try { persistStatistics(); }
            catch (IOException ex) {
                STATS.remove("player." + id + "." + claimed);
                System.err.println("[AbyssCrate] Cannot persist milestone claim: " + ex.getMessage());
                continue;
            }
            try {
                String expanded = command.replace("{player}", player.getName().getString());
                player.level().getServer().getCommands().performPrefixedCommand(
                    player.level().getServer().createCommandSourceStack().withSuppressedOutput(), expanded);
                player.sendSystemMessage(colored("&a✦ Milestone de &e" + threshold + " &apuncte atins! Recompensa acordata."));
            } catch (Exception ex) {
                System.err.println("[AbyssCrate] Milestone reward command failed: " + ex.getMessage());
            }
        }
    }

    // 3S.2: 28 consecutive levels per page, a serpentine route through four rows.
    // Levels 1..200 are always shown; reward thresholds can be added at any level.
    private static final int[] MILESTONE_PATH = {
        10,11,12,13,14,15,16,
        25,24,23,22,21,20,19,
        28,29,30,31,32,33,34,
        43,42,41,40,39,38,37
    };

    private static int milestoneMaxLevel() {
        int configured = 200;
        try { configured = Math.max(200, Integer.parseInt(MILESTONES.getProperty("max-level", "200"))); }
        catch (NumberFormatException ignored) {}
        for (int threshold : milestoneThresholds()) configured = Math.max(configured, threshold);
        return configured;
    }

    private static void openMilestones(ServerPlayer player) {
        openMilestones(player, 0);
    }

    private static void openMilestones(ServerPlayer player, int requestedPage) {
        String id = player.getUUID().toString();
        long points = stat("player." + id + ".total");
        int maxLevel = milestoneMaxLevel();
        int pageCount = (maxLevel + MILESTONE_PATH.length - 1) / MILESTONE_PATH.length;
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        SimpleContainer view = new SimpleContainer(54);
        view.setItem(4, button(Items.NETHER_STAR, "Milestone Level: " + points + " | " + maxLevel));
        for (int i = 0; i < MILESTONE_PATH.length; i++) {
            int level = page * MILESTONE_PATH.length + i + 1;
            if (level > maxLevel) break;
            String command = MILESTONES.getProperty("reward." + level, "").trim();
            boolean reward = !command.isEmpty();
            boolean reached = points >= level;
            boolean claimed = "true".equals(STATS.getProperty("player." + id + ".milestone." + level));
            ItemStack icon = button(reward ? Items.GOLD_BLOCK : Items.QUARTZ_BLOCK,
                "Level " + level + (reward ? " | REWARD" : "") + (reached ? " | Completed" : " | Locked"));
            if (reached) icon.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                colored(reached ? "&aLevel reached" : "&7Progress: &f" + points + "&7/&f" + level),
                colored(reward ? (claimed ? "&aReward claimed" : "&eReward at this level") : "&7Regular milestone"),
                colored(reward ? "&7Reward: &f" + command : "&7Open crates to advance")
            )));
            view.setItem(MILESTONE_PATH[i], icon);
        }
        if (page > 0) view.setItem(45, button(Items.ARROW, "Previous Page"));
        view.setItem(49, button(Items.BOOK, "Page " + (page + 1) + "/" + pageCount + " | Level " + points));
        if (page + 1 < pageCount) view.setItem(53, button(Items.ARROW, "Next Page"));
        showStatsMenu(player, "AbyssCrate | Milestones | " + (page + 1) + "/" + pageCount, view, slot -> {
            if (slot == 45 && page > 0) openMilestones(player, page - 1);
            else if (slot == 53 && page + 1 < pageCount) openMilestones(player, page + 1);
        });
    }

    private static void openMilestoneAdmin(ServerPlayer admin) {
        SimpleContainer view = new SimpleContainer(54);
        view.setItem(4, button(Items.COMMAND_BLOCK, "Configure: /crate milestoneadmin set <points> <command>"));
        List<Integer> thresholds = milestoneThresholds();
        for (int i = 0; i < Math.min(45, thresholds.size()); i++) {
            int n = thresholds.get(i);
            view.setItem(i + 9, button(Items.GOLD_INGOT, n + " points: " + MILESTONES.getProperty("reward." + n)));
        }
        view.setItem(49, button(Items.BARRIER, "Close"));
        showStatsMenu(admin, "AbyssCrate | Milestone Admin", view, slot -> {
            if (slot == 49) admin.closeContainer();
        });
    }

    private static void showStatsMenu(ServerPlayer viewer, String title, SimpleContainer view, java.util.function.IntConsumer handler) {
        viewer.openMenu(new SimpleMenuProvider((sync, inv, player) -> new ChestMenu(MenuType.GENERIC_9x6, sync, inv, view, 6) {
            @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                if (slot >= 0 && slot < 54) { handler.accept(slot); return; }
                super.clicked(slot, button, input, clicker);
            }
            @Override public ItemStack quickMoveStack(Player clicker, int slot) { return ItemStack.EMPTY; }
        }, Component.literal(title)));
    }

    private static void openStatistics(ServerPlayer viewer, UUID target, String name) {
        String id = target.toString();
        SimpleContainer view = new SimpleContainer(54);
        view.setItem(4, button(Items.PLAYER_HEAD, "Statistics: " + name));
        view.setItem(20, button(Items.CHEST, "Total openings: " + stat("player." + id + ".total")));
        view.setItem(22, button(Items.NETHER_STAR, "LEGENDARY wins: " + stat("player." + id + ".rarity.LEGENDARY")));
        view.setItem(24, button(Items.EMERALD, "EPIC wins: " + stat("player." + id + ".rarity.EPIC")));
        List<String> crates = new ArrayList<>();
        synchronized (CRATES) { for (Object key : CRATES.keySet()) crates.add(key.toString()); }
        crates.sort(String::compareTo);
        for (int i = 0; i < Math.min(9, crates.size()); i++) view.setItem(27 + i, button(Items.TRIPWIRE_HOOK, crates.get(i) + ": " + stat("player." + id + ".crate." + crates.get(i))));
        view.setItem(45, button(Items.BOOK, "Reward History"));
        view.setItem(49, button(Items.GOLD_INGOT, "Leaderboards"));
        showStatsMenu(viewer, "AbyssCrate | Stats | " + name, view, slot -> {
            if (slot == 45) openHistory(viewer, target, name, 0);
            else if (slot == 49) openLeaderboard(viewer, false);
        });
    }

    private static void openHistory(ServerPlayer viewer, UUID target, String name, int page) {
        String id = target.toString();
        int count;
        try { count = Integer.parseInt(HISTORY.getProperty(id + ".count", "0")); } catch (NumberFormatException ex) { count = 0; }
        int maxPage = Math.max(0, (count - 1) / 45);
        int current = Math.max(0, Math.min(page, maxPage));
        SimpleContainer view = new SimpleContainer(54);
        for (int i = 0; i < 45 && current * 45 + i < count; i++) {
            String raw = HISTORY.getProperty(id + "." + (current * 45 + i), "");
            String[] fields = raw.split("\\|", 5);
            if (fields.length != 5) continue;
            String date;
            try { date = java.time.Instant.ofEpochMilli(Long.parseLong(fields[0])).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString(); }
            catch (Exception ex) { date = "Unknown date"; }
            view.setItem(i, button(Items.PAPER, fields[2] + " | " + fields[3] + "x " + fields[4] + " | " + fields[1] + " | " + date));
        }
        view.setItem(45, button(Items.ARROW, "Previous page"));
        view.setItem(49, button(Items.BARRIER, "Back to statistics"));
        view.setItem(53, button(Items.ARROW, "Next page"));
        showStatsMenu(viewer, "History | " + name + " | " + (current + 1) + "/" + (maxPage + 1), view, slot -> {
            if (slot == 45) openHistory(viewer, target, name, current - 1);
            else if (slot == 49) openStatistics(viewer, target, name);
            else if (slot == 53) openHistory(viewer, target, name, current + 1);
        });
    }

    private static List<String> leaderboardIds(String field) {
        List<String> ids = new ArrayList<>();
        for (String key : STATS.stringPropertyNames()) {
            if (key.startsWith("player.") && key.endsWith(".name")) ids.add(key.substring(7, key.length() - 5));
        }
        ids.sort((a, b) -> Long.compare(stat("player." + b + "." + field), stat("player." + a + "." + field)));
        return ids;
    }

    private static void openLeaderboard(ServerPlayer viewer, boolean legendary) {
        String field = legendary ? "rarity.LEGENDARY" : "total";
        SimpleContainer view = new SimpleContainer(54);
        List<String> ids = leaderboardIds(field);
        for (int i = 0; i < Math.min(10, ids.size()); i++) {
            String id = ids.get(i);
            view.setItem(10 + (i / 7) * 9 + (i % 7), button(Items.GOLD_INGOT, "#" + (i + 1) + " " + STATS.getProperty("player." + id + ".name", "Unknown") + " - " + stat("player." + id + "." + field)));
        }
        view.setItem(45, button(Items.CHEST, "Top total openings"));
        view.setItem(53, button(Items.NETHER_STAR, "Top LEGENDARY wins"));
        showStatsMenu(viewer, legendary ? "AbyssCrate | Top LEGENDARY" : "AbyssCrate | Top Openings", view, slot -> {
            if (slot == 45) openLeaderboard(viewer, false);
            else if (slot == 53) openLeaderboard(viewer, true);
        });
    }

    private static void openAdminStatistics(ServerPlayer viewer) {
        SimpleContainer view = new SimpleContainer(54);
        view.setItem(4, button(Items.NETHER_STAR, "Global openings: " + stat("global.total")));
        view.setItem(13, button(Items.GOLD_INGOT, "Global LEGENDARY: " + stat("global.rarity.LEGENDARY")));
        List<String> crates = new ArrayList<>();
        synchronized (CRATES) { for (Object key : CRATES.keySet()) crates.add(key.toString()); }
        crates.sort(String::compareTo);
        for (int i = 0; i < Math.min(27, crates.size()); i++) view.setItem(18 + i, button(Items.CHEST, crates.get(i) + " openings: " + stat("global.crate." + crates.get(i))));
        view.setItem(49, button(Items.GOLD_INGOT, "Open leaderboards"));
        showStatsMenu(viewer, "AbyssCrate | Admin Statistics", view, slot -> { if (slot == 49) openLeaderboard(viewer, false); });
    }

    // 4G.1: Battle selection, invitations and resolution.
    private static int countBattleKeys(ServerPlayer player, String crate) {
        int amount = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isKeyFor(stack, crate)) amount += stack.getCount();
        }
        return amount;
    }

    private static List<String> battleCrates() {
        List<String> crates = new ArrayList<>();
        synchronized (CRATES) { for (String name : CRATES.stringPropertyNames()) crates.add(name); }
        crates.sort(String::compareTo);
        return crates;
    }

    private static void openBattlePlayers(ServerPlayer player, int page) {
        List<ServerPlayer> online = new ArrayList<>();
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers())
            if (!other.getUUID().equals(player.getUUID())) online.add(other);
        online.sort(Comparator.comparing(p -> p.getName().getString().toLowerCase(Locale.ROOT)));
        int maxPage = Math.max(0, (online.size() - 1) / 45);
        int current = Math.max(0, Math.min(page, maxPage));
        SimpleContainer view = new SimpleContainer(54);
        for (int i = 0; i < 45 && current * 45 + i < online.size(); i++) {
            ServerPlayer other = online.get(current * 45 + i);
            List<Component> lore = new ArrayList<>();
            boolean compatible = false;
            for (String crate : battleCrates()) {
                int mine = countBattleKeys(player, crate), theirs = countBattleKeys(other, crate);
                if (theirs > 0) lore.add(Component.literal(crate + ": " + theirs + " keys" + (mine > 0 ? " | YOU: " + mine : " | YOU: 0")));
                if (mine > 0 && theirs > 0) compatible = true;
            }
            if (lore.isEmpty()) lore.add(Component.literal("No AbyssCrate keys"));
            lore.add(Component.literal(compatible ? "Click to choose a shared crate" : "No matching keys"));
            ItemStack icon = button(compatible ? Items.PLAYER_HEAD : Items.BARRIER, other.getName().getString());
            icon.set(DataComponents.LORE, new ItemLore(lore));
            view.setItem(i, icon);
        }
        view.setItem(45, button(Items.ARROW, "Previous page"));
        view.setItem(49, button(Items.BARRIER, "Close"));
        view.setItem(53, button(Items.ARROW, "Next page"));
        showStatsMenu(player, "AbyssCrate | Battle Players", view, slot -> {
            if (slot >= 0 && slot < 45 && current * 45 + slot < online.size())
                openBattleCrates(player, online.get(current * 45 + slot).getUUID(), 0);
            else if (slot == 45) openBattlePlayers(player, current - 1);
            else if (slot == 49) player.closeContainer();
            else if (slot == 53) openBattlePlayers(player, current + 1);
        });
    }

    private static void openBattleCrates(ServerPlayer player, UUID targetId, int page) {
        ServerPlayer target = player.level().getServer().getPlayerList().getPlayer(targetId);
        if (target == null || targetId.equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("[AbyssCrate] Player unavailable.")); return;
        }
        List<String> crates = battleCrates();
        int maxPage = Math.max(0, (crates.size() - 1) / 45);
        int current = Math.max(0, Math.min(page, maxPage));
        SimpleContainer view = new SimpleContainer(54);
        for (int i = 0; i < 45 && current * 45 + i < crates.size(); i++) {
            String crate = crates.get(current * 45 + i);
            int mine = countBattleKeys(player, crate), theirs = countBattleKeys(target, crate);
            ItemStack icon = button(mine > 0 && theirs > 0 ? Items.CHEST : Items.BARRIER,
                crate + " | You: " + mine + " | " + target.getName().getString() + ": " + theirs);
            view.setItem(i, icon);
        }
        view.setItem(45, button(Items.ARROW, "Previous"));
        view.setItem(49, button(Items.BARRIER, "Back"));
        view.setItem(53, button(Items.ARROW, "Next"));
        showStatsMenu(player, "Battle vs " + target.getName().getString(), view, slot -> {
            if (slot >= 0 && slot < 45 && current * 45 + slot < crates.size())
                inviteBattle(player, targetId, crates.get(current * 45 + slot));
            else if (slot == 45) openBattleCrates(player, targetId, current - 1);
            else if (slot == 49) openBattlePlayers(player, 0);
            else if (slot == 53) openBattleCrates(player, targetId, current + 1);
        });
    }

    private static void inviteBattle(ServerPlayer challenger, UUID targetId, String crate) {
        ServerPlayer target = challenger.level().getServer().getPlayerList().getPlayer(targetId);
        if (target == null || targetId.equals(challenger.getUUID())) {
            challenger.sendSystemMessage(Component.literal("[AbyssCrate] Player unavailable.")); return;
        }
        if (countBattleKeys(challenger, crate) < 1 || countBattleKeys(target, crate) < 1) {
            challenger.sendSystemMessage(Component.literal("[AbyssCrate] Both players need a " + crate + " key.")); return;
        }
        if (ACTIVE_OPENINGS.containsKey(challenger.getUUID()) || ACTIVE_OPENINGS.containsKey(targetId)) {
            challenger.sendSystemMessage(Component.literal("[AbyssCrate] A player is opening a crate.")); return;
        }
        BATTLE_INVITES.put(targetId, new BattleInvite(challenger.getUUID(), targetId, crate,
            System.currentTimeMillis() + BATTLE_INVITE_TIMEOUT_MS));
        challenger.closeContainer();
        challenger.sendSystemMessage(Component.literal("[AbyssCrate] Invited " + target.getName().getString() + " to " + crate + " battle (60s)."));
        target.sendSystemMessage(Component.literal("[AbyssCrate] " + challenger.getName().getString() + " invited you to a " + crate + " battle! /crate battle accept or /crate battle deny"));
        openBattleInvitation(target, challenger.getName().getString(), crate);
    }

    private static void openBattleInvitation(ServerPlayer target, String challengerName, String crate) {
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(11, button(Items.EMERALD, "ACCEPT " + crate + " vs " + challengerName));
        view.setItem(15, button(Items.REDSTONE, "DENY"));
        target.openMenu(new SimpleMenuProvider((id, inv, viewer) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                if (slot >= 0 && slot < 27) {
                    if (slot == 11) acceptBattle(target);
                    else if (slot == 15) denyBattle(target);
                    return;
                }
                super.clicked(slot, button, input, clicker);
            }
            @Override public ItemStack quickMoveStack(Player clicker, int slot) { return ItemStack.EMPTY; }
        }, Component.literal("AbyssCrate | Battle Invite")));
    }

    private static int denyBattle(ServerPlayer target) {
        BattleInvite invite = BATTLE_INVITES.remove(target.getUUID());
        target.closeContainer();
        target.sendSystemMessage(Component.literal("[AbyssCrate] Battle invitation declined."));
        if (invite != null) {
            ServerPlayer challenger = target.level().getServer().getPlayerList().getPlayer(invite.challenger());
            if (challenger != null) challenger.sendSystemMessage(Component.literal("[AbyssCrate] Battle invitation declined."));
        }
        return 1;
    }

    private static int acceptBattle(ServerPlayer target) {
        BattleInvite invite = BATTLE_INVITES.remove(target.getUUID());
        if (invite == null || System.currentTimeMillis() > invite.expiresAt()) {
            target.sendSystemMessage(Component.literal("[AbyssCrate] No active invitation (or it expired).")); return 0;
        }
        ServerPlayer challenger = target.level().getServer().getPlayerList().getPlayer(invite.challenger());
        if (challenger == null || challenger.getUUID().equals(target.getUUID())) {
            target.sendSystemMessage(Component.literal("[AbyssCrate] Challenger disconnected.")); return 0;
        }
        String crate = invite.crate();
        if (ACTIVE_OPENINGS.containsKey(challenger.getUUID()) || ACTIVE_OPENINGS.containsKey(target.getUUID())
            || BATTLE_VISUALS.containsKey(challenger.getUUID()) || BATTLE_VISUALS.containsKey(target.getUUID())) {
            target.sendSystemMessage(Component.literal("[AbyssCrate] A player is already opening a crate.")); return 0;
        }
        if (countBattleKeys(challenger, crate) < 1 || countBattleKeys(target, crate) < 1) {
            target.sendSystemMessage(Component.literal("[AbyssCrate] Both players must still have the same crate key.")); return 0;
        }
        RewardChoice first = chooseReward(crate), second = chooseReward(crate);
        if (first == null || second == null) {
            target.sendSystemMessage(Component.literal("[AbyssCrate] Crate has no valid rewards.")); return 0;
        }
        // The winner needs capacity for both drops. We do not consume either key otherwise.
        boolean challengerWins = first.weight() < second.weight() ||
            (first.weight() == second.weight() && ThreadLocalRandom.current().nextBoolean());
        ServerPlayer winner = challengerWins ? challenger : target;
        if (emptyBattleSlots(winner) < 2) {
            target.sendSystemMessage(Component.literal("[AbyssCrate] Winner needs two empty inventory slots. Battle cancelled, no keys used."));
            challenger.sendSystemMessage(Component.literal("[AbyssCrate] Battle cancelled: winner needs two empty inventory slots."));
            return 0;
        }
        // One server tick, both inventories validated before consuming either key.
        if (!takeBattleKey(challenger, crate) || !takeBattleKey(target, crate)) {
            target.sendSystemMessage(Component.literal("[AbyssCrate] Battle cancelled: keys changed.")); return 0;
        }
        ItemStack prize1 = new ItemStack(first.item(), first.count());
        ItemStack prize2 = new ItemStack(second.item(), second.count());
        winner.getInventory().add(prize1);
        winner.getInventory().add(prize2);
        recordOpening(challenger, crate, first, new ItemStack(first.item()).getHoverName().getString());
        recordOpening(target, crate, second, new ItemStack(second.item()).getHoverName().getString());
        awardMilestones(challenger);
        awardMilestones(target);
        challenger.closeContainer(); target.closeContainer();
        String result = "[AbyssCrate] " + challenger.getName().getString() + " (" + first.weight() + " weight) vs "
            + target.getName().getString() + " (" + second.weight() + " weight). WINNER: " + winner.getName().getString()
            + "! Won " + first.count() + "x " + new ItemStack(first.item()).getHoverName().getString() + " + "
            + second.count() + "x " + new ItemStack(second.item()).getHoverName().getString() + ". Both earned +1 milestone point.";
        challenger.sendSystemMessage(Component.literal(result));
        target.sendSystemMessage(Component.literal(result));
        recordBattleOutcome(challenger, target, challengerWins);
        startBattleVisual(challenger, target, crate, first, second, challengerWins);
        startBattleVisual(target, challenger, crate, second, first, !challengerWins);
        return 1;
    }

    // 4G.3: persist battle results alongside existing statistics (same UUID storage).
    private static synchronized void recordBattleOutcome(ServerPlayer first, ServerPlayer second, boolean firstWins) {
        updateBattleStats(first, firstWins);
        updateBattleStats(second, !firstWins);
        try { persistStatistics(); } catch (IOException ex) {
            System.err.println("[AbyssCrate] Cannot save battle statistics: " + ex.getMessage());
        }
    }

    private static void updateBattleStats(ServerPlayer player, boolean won) {
        String base = "player." + player.getUUID() + ".battle.";
        STATS.setProperty("player." + player.getUUID() + ".name", player.getName().getString());
        incrementStat(base + "total");
        incrementStat(base + (won ? "wins" : "losses"));
        long streak = won ? stat(base + "streak") + 1 : 0;
        STATS.setProperty(base + "streak", Long.toString(streak));
        if (streak > stat(base + "best")) STATS.setProperty(base + "best", Long.toString(streak));
    }

    private static void openBattleStats(ServerPlayer viewer, ServerPlayer target) {
        String base = "player." + target.getUUID() + ".battle.";
        long total = stat(base + "total"), wins = stat(base + "wins");
        SimpleContainer view = new SimpleContainer(54);
        view.setItem(4, button(Items.PLAYER_HEAD, "Battle stats: " + target.getName().getString()));
        view.setItem(19, button(Items.EMERALD, "Wins: " + wins));
        view.setItem(21, button(Items.REDSTONE, "Losses: " + stat(base + "losses")));
        view.setItem(23, button(Items.CHEST, "Battles: " + total));
        view.setItem(25, button(Items.GOLD_INGOT, "Win rate: " + (total == 0 ? 0 : wins * 100 / total) + "%"));
        view.setItem(30, button(Items.BLAZE_ROD, "Current streak: " + stat(base + "streak")));
        view.setItem(32, button(Items.NETHER_STAR, "Best streak: " + stat(base + "best")));
        view.setItem(49, button(Items.BOOK, "Battle leaderboard"));
        showStatsMenu(viewer, "AbyssCrate | Battle Stats", view, slot -> {
            if (slot == 49) openBattleTop(viewer, 0);
        });
    }

    private static void openBattleTop(ServerPlayer viewer, int page) {
        List<String> ids = new ArrayList<>();
        for (String key : STATS.stringPropertyNames()) {
            if (key.startsWith("player.") && key.endsWith(".battle.wins")) {
                String id = key.substring(7, key.length() - ".battle.wins".length());
                if (stat(key) > 0) ids.add(id);
            }
        }
        ids.sort((a,b) -> {
            int cmp = Long.compare(stat("player." + b + ".battle.wins"), stat("player." + a + ".battle.wins"));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        int max = Math.max(0, (ids.size() - 1) / 45);
        int current = Math.max(0, Math.min(page, max));
        SimpleContainer view = new SimpleContainer(54);
        for (int i = 0; i < 45 && current * 45 + i < ids.size(); i++) {
            String id = ids.get(current * 45 + i);
            String base = "player." + id + ".battle.";
            String name = STATS.getProperty("player." + id + ".name", id);
            view.setItem(i, button(Items.PLAYER_HEAD, "#" + (current * 45 + i + 1) + " " + name
                + " | Wins: " + stat(base + "wins") + " | Losses: " + stat(base + "losses")));
        }
        view.setItem(45, button(Items.ARROW, "Previous"));
        view.setItem(49, button(Items.BARRIER, "Close"));
        view.setItem(53, button(Items.ARROW, "Next"));
        showStatsMenu(viewer, "AbyssCrate | Battle Top", view, slot -> {
            if (slot == 45) openBattleTop(viewer, current - 1);
            else if (slot == 53) openBattleTop(viewer, current + 1);
            else if (slot == 49) viewer.closeContainer();
        });
    }

    // Animation is display-only: settlement happens before GUI starts, preventing duplicate payouts
    // if either participant closes the inventory or disconnects.
    private static void startBattleVisual(ServerPlayer viewer, ServerPlayer opponent, String crate,
                                          RewardChoice own, RewardChoice rival, boolean won) {
        BattleVisual visual = new BattleVisual(opponent.getUUID(), crate, own, rival, won);
        BATTLE_VISUALS.put(viewer.getUUID(), visual);
        viewer.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(
            MenuType.GENERIC_9x3, id, inv, visual.display, 3) {
            @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                if (slot >= 0 && slot < 27) return;
                super.clicked(slot, button, input, clicker);
            }
            @Override public ItemStack quickMoveStack(Player clicker, int slot) { return ItemStack.EMPTY; }
        }, Component.literal("AbyssCrate | Battle Animation")));
    }

    private static void tickBattleVisuals(net.minecraft.server.MinecraftServer server) {
        var iterator = BATTLE_VISUALS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            BattleVisual visual = entry.getValue();
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            if (viewer == null) { iterator.remove(); continue; }
            visual.ticks++;
            if (visual.ticks <= 60) {
                int remaining = Math.max(1, 4 - (visual.ticks + 19) / 20);
                visual.display.setItem(4, button(Items.CLOCK, "Battle starts in " + remaining + "..."));
            } else if (visual.ticks <= 140) {
                if (visual.ticks % 5 == 0) {
                    RewardChoice a = chooseReward(visual.crate);
                    RewardChoice b = chooseReward(visual.crate);
                    if (a != null && b != null) {
                        visual.display.setItem(11, button(a.item(), "YOUR ROLL..."));
                        visual.display.setItem(15, button(b.item(), "OPPONENT ROLL..."));
                        visual.display.setItem(4, button(Items.CLOCK, "Rolling..."));
                    }
                }
            } else {
                iterator.remove();
                String opponent = server.getPlayerList().getPlayer(visual.opponent) == null ? "Opponent"
                    : server.getPlayerList().getPlayer(visual.opponent).getName().getString();
                openBattleResult(viewer, opponent, visual.crate, visual.own, visual.rival, visual.won);
            }
        }
    }

    private static void openBattleResult(ServerPlayer viewer, String opponent, String crate,
                                         RewardChoice own, RewardChoice rival, boolean won) {
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(11, button(own.item(), "YOUR DROP: " + own.count() + "x "
            + new ItemStack(own.item()).getHoverName().getString() + " | Weight: " + own.weight()));
        view.setItem(15, button(rival.item(), "OPPONENT DROP: " + rival.count() + "x "
            + new ItemStack(rival.item()).getHoverName().getString() + " | Weight: " + rival.weight()));
        view.setItem(4, button(won ? Items.NETHER_STAR : Items.BARRIER,
            (won ? "VICTORY! Both rewards are yours" : "DEFEAT! Opponent won both rewards") + " | +1 Milestone"));
        view.setItem(22, button(Items.BOOK, "Crate: " + crate + " | Opponent: " + opponent));
        view.setItem(26, button(Items.OAK_DOOR, "Close"));
        viewer.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                if (slot >= 0 && slot < 27) { if (slot == 26) viewer.closeContainer(); return; }
                super.clicked(slot, button, input, clicker);
            }
            @Override public ItemStack quickMoveStack(Player clicker, int slot) { return ItemStack.EMPTY; }
        }, Component.literal("AbyssCrate | Battle Result")));
    }

    private static int emptyBattleSlots(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).isEmpty()) count++;
        return count;
    }

    private static boolean takeBattleKey(ServerPlayer player, String crate) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isKeyFor(stack, crate)) { stack.shrink(1); return true; }
        }
        return false;
    }

    // Reward chances are relative weights: e.g. 10 and 30 => 25% / 75%.
    // Zero-weight rewards cannot be selected. All-zero / missing rewards give nothing.
    private record RewardChoice(int id, Item item, int count, int weight) { }

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
                    choices.add(new RewardChoice(id, item, count, weight));
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

    // 3P.1: Key identity is independent of material/name/glint.
    private static synchronized void loadKeys() throws IOException {
        Files.createDirectories(DIR);
        KEYS.clear();
        if (Files.exists(KEYS_FILE)) try (InputStream in = Files.newInputStream(KEYS_FILE)) { KEYS.load(in); }
    }

    private static synchronized void saveKeys() throws IOException {
        Files.createDirectories(DIR);
        try (OutputStream out = Files.newOutputStream(KEYS_FILE)) { KEYS.store(out, "AbyssCrate custom keys - supports HEX and Birdflop gradients"); }
    }

    private static synchronized String keySetting(String crate, String field, String fallback) {
        return KEYS.getProperty(crate + "." + field, fallback);
    }

    private static Item keyMaterial(String crate) {
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(keySetting(crate, "material", "minecraft:tripwire_hook")));
            return item == null || item == Items.AIR ? Items.TRIPWIRE_HOOK : item;
        } catch (Exception ex) { return Items.TRIPWIRE_HOOK; }
    }

    private static ItemStack createKey(String name, int amount) {
        ItemStack stack = new ItemStack(keyMaterial(name), amount);
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_TAG, name);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        stack.set(DataComponents.CUSTOM_NAME, colored(keySetting(name, "name", "Cheie " + name.toUpperCase(Locale.ROOT))));
        List<Component> lore = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String line = keySetting(name, "lore." + i, "");
            if (!line.isEmpty()) lore.add(colored(line));
        }
        if (!lore.isEmpty()) stack.set(DataComponents.LORE, new ItemLore(lore));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.parseBoolean(keySetting(name, "glint", "false")));
        return stack;
    }

    private static boolean isKeyFor(ItemStack stack, String name) {
        // Do not require a specific material: old issued keys stay valid after design changes.
        if (stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.copyTag().getString(KEY_TAG).map(name::equals).orElse(false);
    }

    private static int updateKeyCommand(net.minecraft.commands.CommandSourceStack source, String rawCrate, String field, String value) {
        String crate = rawCrate.toLowerCase(Locale.ROOT);
        synchronized (CRATES) {
            if (!CRATES.containsKey(crate)) { reply(source, "not-found", crate); return 0; }
        }
        if (value.length() > 240 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            source.sendFailure(Component.literal("Maximum 240 caractere pe camp.")); return 0;
        }
        if (field.equals("material")) {
            try {
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(value));
                if (item == null || item == Items.AIR) throw new IllegalArgumentException("Unknown item");
                value = BuiltInRegistries.ITEM.getKey(item).toString();
            } catch (Exception ex) { source.sendFailure(Component.literal("Material invalid: " + value)); return 0; }
        }
        if (!saveKeyField(crate, field, value)) {
            source.sendFailure(Component.literal("Nu am putut salva keys.properties.")); return 0;
        }
        source.sendSuccess(() -> Component.literal("[AbyssCrate] Cheia " + crate + ": " + field + " actualizat."), false);
        return 1;
    }

    private static synchronized boolean saveKeyField(String crate, String field, String value) {
        String property = crate + "." + field;
        Object previous = KEYS.setProperty(property, value);
        try { saveKeys(); return true; }
        catch (IOException ex) {
            if (previous == null) KEYS.remove(property); else KEYS.put(property, previous);
            return false;
        }
    }

    private static void openKeyEditor(ServerPlayer admin, String crate) {
        SimpleContainer view = new SimpleContainer(27);
        view.setItem(10, button(Items.NAME_TAG, "Name: " + keySetting(crate, "name", "Cheie " + crate) + " | click: copy held item name"));
        view.setItem(11, button(Items.BOOK, "Lore | click: copy held item lore (max 10 lines)"));
        view.setItem(12, button(keyMaterial(crate), "Material: " + keySetting(crate, "material", "minecraft:tripwire_hook") + " | click: use held item"));
        view.setItem(13, button(Items.ENCHANTED_BOOK, "Glint: " + (Boolean.parseBoolean(keySetting(crate, "glint", "false")) ? "ON" : "OFF") + " | click: toggle"));
        view.setItem(15, createKey(crate, 1));
        view.setItem(16, button(Items.PAPER, "For custom HEX name/lore use /crate key " + crate + " name/lore add"));
        view.setItem(22, button(Items.ARROW, "Back to editor"));
        admin.openMenu(new SimpleMenuProvider((id, inv, player) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, view, 3) {
            @Override public void clicked(int slot, int button, ContainerInput input, Player clicker) {
                if (slot >= 0 && slot < 27) {
                    if (slot == 22) { openMainEditor(admin, crate); return; }
                    if (slot == 13) saveKeyField(crate, "glint", Boolean.toString(!Boolean.parseBoolean(keySetting(crate, "glint", "false"))));
                    if (slot == 10 || slot == 11 || slot == 12) {
                        ItemStack held = admin.getMainHandItem();
                        if (held.isEmpty()) { admin.sendSystemMessage(Component.literal("[AbyssCrate] Tine un item in mana principala.")); return; }
                        if (slot == 10) saveKeyField(crate, "name", held.getHoverName().getString());
                        if (slot == 12) saveKeyField(crate, "material", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
                        if (slot == 11) {
                            ItemLore itemLore = held.get(DataComponents.LORE);
                            if (itemLore == null || itemLore.lines().isEmpty()) {
                                admin.sendSystemMessage(Component.literal("[AbyssCrate] Itemul nu are lore. Foloseste /crate key " + crate + " lore add <1-10> <text>."));
                                return;
                            }
                            for (int i = 1; i <= 10; i++) saveKeyField(crate, "lore." + i, i <= itemLore.lines().size() ? itemLore.lines().get(i - 1).getString() : "");
                        }
                    }
                    openKeyEditor(admin, crate);
                    return;
                }
                super.clicked(slot, button, input, clicker);
            }
            @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        }, Component.literal("AbyssCrate Key | " + crate)));
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
        defaults.setProperty("preview.title", "&#004FFF✦ &#00B7FF{crate} &#004FFF✦ &#AAAAAA{page}/{total}");
        defaults.setProperty("preview.reward-name", "&#FFD700Recompensa #{id}");
        defaults.setProperty("preview.chance", "&#FFD700Șansă: &#FFFFFF{chance}%");
        defaults.setProperty("preview.amount", "&#55FF55Cantitate: &#FFFFFF{amount}");
        defaults.setProperty("preview.readonly", "&#FF5555Doar vizualizare");
        defaults.setProperty("preview.previous", "&#00B7FFPagina anterioară");
        defaults.setProperty("preview.next", "&#00B7FFPagina următoare");
        defaults.setProperty("preview.page", "&#FFD700Pagina {page}/{total} &#AAAAAA| {rewards} recompense");
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
        // Add new preview message keys to existing configurations without overwriting custom messages.
        Properties additions = new Properties();
        for (String key : defaults.stringPropertyNames()) {
            if (key.startsWith("preview.") && !next.containsKey(key)) {
                additions.setProperty(key, defaults.getProperty(key));
            }
        }
        if (!additions.isEmpty()) {
            Properties stored = new Properties();
            try (InputStream in = Files.newInputStream(MESSAGES_FILE)) { stored.load(in); }
            for (String key : additions.stringPropertyNames()) stored.setProperty(key, additions.getProperty(key));
            try (OutputStream out = Files.newOutputStream(MESSAGES_FILE)) {
                stored.store(out, "AbyssCrate messages - edit and use /crate reload");
            }
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
