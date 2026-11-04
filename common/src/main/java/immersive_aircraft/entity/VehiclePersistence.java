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
 * <p>Vanilla treats a vehicle with exactly one player rider as belonging to that player, using a
 * three step handshake that AntiLogout's ghost player knocks out of order:
 * <ul>
 *   <li>{@code Entity#shouldBeSaved()} returns {@code false}, so the aircraft is <em>not</em> written
 *       to chunk data - it only survives inside the player's {@code RootVehicle} NBT.</li>
 *   <li>{@code PlayerList#remove()} strips it from the world with
 *       {@code RemovalReason.UNLOADED_WITH_PLAYER}.</li>
 *   <li>{@code PlayerList#placeNewPlayer()} rebuilds it from that {@code RootVehicle} NBT, and if
 *       reattachment fails it calls {@code entity.discard()} - destroying the aircraft and leaving the
 *       player standing at their old altitude with no mount, falling to their death.</li>
 * </ul>
 *
 * <p>AntiLogout cancels {@code onDisconnect}, so the ghost stays in the world still mounted and
 * {@code PlayerList#remove()} only runs later, from {@code getPlayerForLogin()}. The three steps no
 * longer happen in a consistent order, so the aircraft regularly ends up neither saved nor restored.
 *
 * <p>We sidestep the handshake entirely: {@code EntityMixin} makes aircraft behave like multi-rider
 * vehicles (always saved to chunk data, never stripped on logout), and this class remembers which
 * aircraft each player was on so it can re-attach them the moment they rejoin. The aircraft is never
 * destroyed, so it cannot disappear.
 */
public final class VehiclePersistence {

    /** How long after joining a player is shielded from the fall they may inherit from a lost mount. */
    private static final long JOIN_GRACE_MS = 20000;

    /**
     * Player UUID to the UUID of the {@link VehicleEntity} they were last riding.
     *
     * <p>Written while the player is online. It survives their disconnect because the ghost player
     * is still in {@code PlayerList#getPlayers()} (AntiLogout cancels the removal) and therefore
     * keeps being tracked; by the time the ghost is dropped during relogin, this record has already
     * served its purpose.
     */
    private static final Map<UUID, UUID> LAST_VEHICLE = new HashMap<>();

    /** Players that joined moments ago and must not be killed by the rejoin fall. */
    private static final Map<UUID, Long> JOIN_GRACE_UNTIL = new HashMap<>();

    private VehiclePersistence() {
    }

    /**
     * Called every server tick for each online player.
     */
    public static void trackTick(ServerPlayer player) {
        if (player.isPassenger() && player.getVehicle() instanceof VehicleEntity vehicle) {
            LAST_VEHICLE.put(player.getUUID(), vehicle.getUUID());
        } else {
            // On foot: a stale record must not resurface and mount them to the wrong aircraft.
            LAST_VEHICLE.remove(player.getUUID());
        }
    }

    /**
     * Called once a player has finished joining - i.e. after vanilla's own {@code RootVehicle}
     * restore has already run and, for us, deliberately done nothing.
     */
    public static void onJoin(ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        UUID vehicleId = LAST_VEHICLE.remove(playerUuid);

        // Always open the grace window: even a successful mount can leave the client a tick or two
        // behind, and a lost mount must never be lethal.
        JOIN_GRACE_UNTIL.put(playerUuid, System.currentTimeMillis() + JOIN_GRACE_MS);

        if (player.isPassenger()) {
            return;
        }

        VehicleEntity vehicle = null;

        if (vehicleId != null) {
            Entity entity = player.serverLevel().getEntity(vehicleId);
            if (entity instanceof VehicleEntity candidate && !candidate.isRemoved()) {
                vehicle = candidate;
            }
        }

        if (vehicle == null) {
            // The record is in-memory, so it does not survive a server restart - but the player
            // still respawns at the exact spot they were riding from, standing inside their own
            // aircraft. Any aircraft they are inside at that moment has to be the one they left.
            vehicle = findVehicleContaining(player);
        }

        if (vehicle != null) {
            player.startRiding(vehicle, true);
        }

        // Passengers never build up fall distance, so anything carried into the rejoin is phantom
        // bookkeeping from the logout itself rather than a fall the player actually made.
        player.resetFallDistance();
    }

    private static VehicleEntity findVehicleContaining(ServerPlayer player) {
        List<VehicleEntity> candidates = player.level().getEntities(
                EntityTypeTest.forClass(VehicleEntity.class),
                player.getBoundingBox(),
                vehicle -> vehicle.getBoundingBox().contains(player.position())
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
