package immersive_aircraft.entity;

import net.minecraft.server.level.ServerPlayer;
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
 * class retries the re-attach every tick for a short grace window after join, and shields the
 * player from fall damage until they are mounted (or the window closes).
 */
public final class VehiclePersistence {

    /** How long after joining a player may still be re-attached (and is shielded from falling). */
    private static final long JOIN_GRACE_MS = 20000;

    /**
     * Player UUID to the UUID of the {@link VehicleEntity} they were last riding.
     *
     * <p>Written while the player is online (including AntiLogout's ghost, which stays in
     * {@code PlayerList#getPlayers()}). Kept while the player is within their post-join grace
     * window so re-attachment can retry across ticks; dropped as soon as they are on foot outside
     * it, so a stale record never resurfaces and mounts them to the wrong aircraft later.
     */
    private static final Map<UUID, UUID> LAST_VEHICLE = new HashMap<>();

    /** Players that joined moments ago, are not mounted yet, and must not be killed by the rejoin fall. */
    private static final Map<UUID, Long> JOIN_GRACE_UNTIL = new HashMap<>();

    private VehiclePersistence() {
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
            // Mounted: there is no fall to shield, and jumping back out must stay lethal.
            JOIN_GRACE_UNTIL.remove(playerUuid);
            return;
        }

        if (inJoinGrace(player)) {
            // Rejoined but not mounted yet - retry every tick, the aircraft's chunk and its
            // entities may still be loading (always true after a server restart).
            VehicleEntity vehicle = resolveVehicle(player);
            if (vehicle != null && player.startRiding(vehicle, true)) {
                player.resetFallDistance();
                JOIN_GRACE_UNTIL.remove(playerUuid);
            }
            return;
        }

        // On foot outside the grace window: a stale record must not resurface later.
        LAST_VEHICLE.remove(playerUuid);
    }

    /**
     * Called once a player has finished joining - i.e. after vanilla's own {@code RootVehicle}
     * restore has already run.
     */
    public static void onJoin(ServerPlayer player) {
        if (player.isPassenger()) {
            return; // vanilla already mounted them; no grace needed
        }
        JOIN_GRACE_UNTIL.put(player.getUUID(), System.currentTimeMillis() + JOIN_GRACE_MS);
        player.resetFallDistance();
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

    /**
     * Whether fall damage should be ignored for this player just after they rejoined.
     */
    public static boolean inJoinGrace(ServerPlayer player) {
        Long until = JOIN_GRACE_UNTIL.get(player.getUUID());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            JOIN_GRACE_UNTIL.remove(player.getUUID());
            return false;
        }
        return true;
    }
}
