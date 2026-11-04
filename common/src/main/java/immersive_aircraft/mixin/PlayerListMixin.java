package immersive_aircraft.mixin;

import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps an aircraft in the world when its rider logs out.
 *
 * <p>{@code PlayerList#remove()} strips a vehicle carrying exactly one player with
 * {@code RemovalReason.UNLOADED_WITH_PLAYER}, on the assumption that the mount will be rebuilt from
 * the player's {@code RootVehicle} NBT when they come back. AntiLogout cancels {@code onDisconnect},
 * so that removal does not happen on logout at all - it happens later, from
 * {@code getPlayerForLogin()}, in the middle of the rejoin - and {@code placeNewPlayer()} can then
 * fail to reattach and call {@code entity.discard()}, destroying the aircraft and leaving the player
 * at their old altitude falling to their death.
 *
 * <p>Leaving the aircraft alone makes both outcomes safe. If it is still in the world, the
 * {@code RootVehicle} restore finds its UUID already taken, quietly skips, and
 * {@link VehiclePersistence} mounts the player onto the real aircraft. If it genuinely is gone, the
 * restore proceeds exactly as vanilla intends.
 *
 * <p>This deliberately touches only the logout path in {@code PlayerList#remove()}. The other two
 * consumers of {@code Entity#hasExactlyOnePlayerPassenger()} - {@code shouldBeSaved()} and the
 * {@code RootVehicle} write in {@code ServerPlayer#addAdditionalSaveData()} - are left alone, so a
 * server restart still restores the mount through vanilla's own machinery.
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Redirect(
            method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hasExactlyOnePlayerPassenger()Z")
    )
    private boolean immersive_aircraft$keepAircraftOnLogout(Entity vehicle) {
        if (vehicle instanceof VehicleEntity) {
            return false;
        }
        return vehicle.hasExactlyOnePlayerPassenger();
    }
}
