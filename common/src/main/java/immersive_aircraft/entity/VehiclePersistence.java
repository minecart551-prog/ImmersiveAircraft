package immersive_aircraft.entity;

import immersive_aircraft.mixin.ServerPlayerDisconnectedAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a player attached to their aircraft across disconnects.
 *
 * <p>{@link immersive_aircraft.mixin.PlayerListMixin} stops the logout path from stripping an
 * aircraft out of the world, so it is still there when the rider comes back - but entity loading is
 * asynchronous, so it may take a few ticks after joining before the aircraft can be found. This
 * class retries the re-attach every tick for a short window after join.
 *
 * <p>If a player rejoins with no mount at their old altitude, they are given Slow Falling instead
 * of fall-damage immunity: they drift down safely and the effect is taken away as soon as their Y
 * stops decreasing (they reached ground) or they are mounted again. Deliberate jumps out of a
 * flying aircraft therefore stay lethal.
 */
public final class VehiclePersistence {

    /** How long after joining re-attachment keeps being retried (covers async chunk/entity loading). */
    private static final long REATTACH_MS = 20000;

    /**
     * Consecutive ticks Y must hold steady - <em>after</em> the player was actually seen falling -
     * before the Slow Falling crutch is taken away. Primary landing signal is {@code onGround()};
     * this is the fallback for settling on water etc. Generous, because right after joining the
     * client can freeze for seconds while loading terrain, during which Y simply does not move.
     */
    private static final int LANDING_STABLE_TICKS = 20;

    /**
     * Player UUID to the UUID of the {@link VehicleEntity} they were last riding.
     *
     * <p>Written while the player is online (including AntiLogout's ghost, which stays in
     * {@code PlayerList#getPlayers()}). Kept while the player is within their post-join re-attach
     * window so retries can run across ticks; dropped as soon as they are on foot outside it, so a
     * stale record never resurfaces and mounts them to the wrong aircraft later.
     */
    private static final Map<UUID, UUID> LAST_VEHICLE = new HashMap<>();

    /** Players that rejoined moments ago and are still waiting to be put back on their aircraft. */
    private static final Map<UUID, Long> REATTACH_UNTIL = new HashMap<>();

    /** Players currently under the Slow Falling crutch, with the Y-stability bookkeeping for it. */
    private static final Map<UUID, FallTracker> FALL_TRACK = new HashMap<>();

    private VehiclePersistence() {
    }

    private static final class FallTracker {
        double lastY;
        int stableTicks;
        /** Whether the client has been observed actually descending since joining. */
        boolean decreased;
        /** Whether the crutch effect currently on the player is one we applied (and may strip). */
        boolean ours;

        FallTracker(double lastY, boolean ours) {
            this.lastY = lastY;
            this.ours = ours;
        }
    }

    /**
     * Called every server tick for each online player.
     */
    public static void trackTick(ServerPlayer player) {
        UUID playerUuid = player.getUUID();

        if (player.isPassenger()) {
            if (player.getVehicle() instanceof VehicleEntity vehicle) {
                LAST_VEHICLE.put(playerUuid, vehicle.getUUID());
            } else {
                LAST_VEHICLE.remove(playerUuid);
            }
            // Mounted: no fall possible, and jumping back out must stay lethal.
            REATTACH_UNTIL.remove(playerUuid);
            stopSlowFalling(player);
            return;
        }

        // AntiLogout's ghost: still in PlayerList#getPlayers() with the vanilla disconnected flag set.
        boolean ghost = ((ServerPlayerDisconnectedAccessor) player).immersive_aircraft$isDisconnected();

        Long until = REATTACH_UNTIL.get(playerUuid);
        if (until != null) {
            if (System.currentTimeMillis() >= until) {
                REATTACH_UNTIL.remove(playerUuid);
            } else {
                // Rejoined but not mounted yet - retry every tick, the aircraft's chunk and its
                // entities may still be loading (always true after a server restart).
                VehicleEntity vehicle = resolveVehicle(player);
                if (vehicle != null && player.startRiding(vehicle, true)) {
                    player.resetFallDistance();
                    REATTACH_UNTIL.remove(playerUuid);
                    stopSlowFalling(player);
                    return;
                }
            }
        } else if (!ghost) {
            // On foot outside the window: a stale record must not resurface later.
            // A ghost keeps its record - it is what puts the player back on their aircraft at rejoin.
            LAST_VEHICLE.remove(playerUuid);
        }

        // Slow Falling crutch: keep it for the whole rejoin descent, take it away once the player
        // has actually landed.
        FallTracker tracker = FALL_TRACK.get(playerUuid);
        if (tracker == null) {
            // Disconnected AntiLogout ghost without a mount under it: it runs vanilla physics via
            // AntiLogout's doTick pump, so give it the same crutch instead of letting it plummet.
            if (ghost && !player.onGround()) {
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, true));
                FALL_TRACK.put(playerUuid, new FallTracker(player.getY(), true));
            }
            return;
        }
        double y = player.getY();
        if (y < tracker.lastY - 1.0E-4) {
            tracker.decreased = true;
            tracker.stableTicks = 0;
        } else {
            tracker.stableTicks++;
        }
        tracker.lastY = y;

        // Reached ground (primary), or settled after a real fall (water etc.).
        if (player.onGround() || (tracker.decreased && tracker.stableTicks >= LANDING_STABLE_TICKS)) {
            stopSlowFalling(player);
            return;
        }

        // Still airborne: keep the effect on them no matter what. The login sequence can freeze
        // Y for seconds (terrain load, move packets not yet arriving) and the effect itself
        // expires - neither may ever end the protection while they are still falling.
        if (player.getEffect(MobEffects.SLOW_FALLING) == null) {
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, true));
            tracker.ours = true;
        }
    }

    /**
     * Called once a player has finished joining - i.e. after vanilla's own {@code RootVehicle}
     * restore has already run.
     */
    public static void onJoin(ServerPlayer player) {
        // Slow Falling arriving with the player is our crutch from the ghost session, carried in
        // their saved data - strip it so every rejoin starts from a clean slate (this may also
        // drop a potion effect they logged out with; rejoin behavior is meant to be predictable).
        player.removeEffect(MobEffects.SLOW_FALLING);

        if (player.isPassenger()) {
            return; // already mounted; no crutch needed
        }
        FALL_TRACK.put(player.getUUID(), new FallTracker(player.getY(), true));
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, true));
        REATTACH_UNTIL.put(player.getUUID(), System.currentTimeMillis() + REATTACH_MS);
        player.resetFallDistance();
    }

    private static void stopSlowFalling(ServerPlayer player) {
        FallTracker tracker = FALL_TRACK.remove(player.getUUID());
        if (tracker != null && tracker.ours) {
            player.removeEffect(MobEffects.SLOW_FALLING);
        }
    }

    private static VehicleEntity resolveVehicle(ServerPlayer player) {
        UUID vehicleId = LAST_VEHICLE.get(player.getUUID());
        if (vehicleId != null) {
            Entity entity = player.serverLevel().getEntity(vehicleId);
            if (entity instanceof VehicleEntity candidate && !candidate.isRemoved()) {
                return candidate;
            }
        }
        // After a restart the in-memory record is gone, but the player respawns at the exact
        // spot they were riding from - standing inside their own aircraft.
        return findVehicleContaining(player);
    }

    private static VehicleEntity findVehicleContaining(ServerPlayer player) {
        List<VehicleEntity> candidates = player.level().getEntities(
                EntityTypeTest.forClass(VehicleEntity.class),
                player.getBoundingBox().inflate(1.0),
                vehicle -> vehicle.getBoundingBox().inflate(1.0).contains(player.position())
        );
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
