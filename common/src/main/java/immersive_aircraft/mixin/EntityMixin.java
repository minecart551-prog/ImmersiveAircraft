package immersive_aircraft.mixin;

import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
    public void immersive_aircraft$preventPlayerStandOnAircraft(Entity other, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof Player player && other instanceof VehicleEntity vehicle) {
            if (!vehicle.hasPassenger(player)) {
                cir.setReturnValue(false);
            }
        }
    }

    /**
     * Makes aircraft behave like multi-rider vehicles with respect to logout.
     *
     * <p>Vanilla answers {@code true} here for a vehicle carrying exactly one player, and all three
     * consumers of that answer assume the mount travels inside the player's {@code RootVehicle} NBT
     * instead of the world:
     * <ul>
     *   <li>{@code Entity#shouldBeSaved()} - stops writing the aircraft to chunk data,</li>
     *   <li>{@code PlayerList#remove()} - strips it from the world on logout,</li>
     *   <li>{@code ServerPlayer#addAdditionalSaveData()} - writes the {@code RootVehicle} copy.</li>
     * </ul>
     *
     * <p>AntiLogout keeps the disconnecting player in the world, so those three steps run out of
     * order and the aircraft can end up neither saved nor restored; {@code placeNewPlayer()} then
     * discards it and the player falls from their old altitude. Answering {@code false} keeps the
     * aircraft in the world and in chunk data, and skips the {@code RootVehicle} copy that would
     * only race with it - {@link VehiclePersistence} re-attaches the player on rejoin instead.
     */
    @Inject(method = "hasExactlyOnePlayerPassenger", at = @At("HEAD"), cancellable = true)
    public void immersive_aircraft$aircraftSurvivesLogout(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof VehicleEntity) {
            cir.setReturnValue(false);
        }
    }
}
