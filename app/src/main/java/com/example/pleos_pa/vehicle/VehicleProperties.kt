package com.example.pleos_pa.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.api.model.capability.SmartGlassConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import com.example.pleos_pa.contract.SmartGlassConfigValue

/** The SDK calls behind one catalog property. */
class SdkProperty(
    val checkCapability: (Vehicle, (PropertyCapability) -> Unit, (Exception) -> Unit) -> Unit,
    val read: (Vehicle, String?, (Any?) -> Unit, (Exception) -> Unit) -> Unit,
)

private fun configOnly(done: (PropertyCapability) -> Unit): (CapabilityConfig) -> Unit = { config ->
    done(PropertyCapability(config.accessType.name))
}

private fun globalList(done: (PropertyCapability) -> Unit): (CapabilityConfig, List<Any?>) -> Unit =
    { config, _ ->
        done(PropertyCapability(config.accessType.name))
    }

private fun <N : Number> globalBounds(
    done: (PropertyCapability) -> Unit
): (CapabilityConfig, AreaBounds<N>) -> Unit = { config, bounds ->
    done(
        PropertyCapability(
            config.accessType.name,
            minimum = contractBound(bounds.minValue),
            maximum = contractBound(bounds.maxValue),
        )
    )
}

private fun <A> areaList(
    done: (PropertyCapability) -> Unit,
    label: (A) -> String,
): (CapabilityConfig, List<A>) -> Unit = { config, areas ->
    done(PropertyCapability(config.accessType.name, areas.map { ReportedArea(label(it)) }))
}

private fun <A, N : Number> areaBounds(
    done: (PropertyCapability) -> Unit,
    label: (A) -> String,
): (CapabilityConfig, Map<A, AreaBounds<N>>) -> Unit = { config, areas ->
    done(
        PropertyCapability(
            config.accessType.name,
            areas.map { (area, bounds) ->
                ReportedArea(
                    label(area),
                    contractBound(bounds.minValue),
                    contractBound(bounds.maxValue),
                )
            },
        )
    )
}

private fun <A> areaValues(
    done: (PropertyCapability) -> Unit,
    label: (A) -> String,
): (CapabilityConfig, Map<A, *>) -> Unit = { config, areas ->
    done(PropertyCapability(config.accessType.name, areas.keys.map { ReportedArea(label(it)) }))
}

/**
 * SDK 2.3.2 has no public capability check for this property, and the contract's OK needs a
 * readable capability. Borrowing another property's check would be a guess, so it is not read.
 */
private val noCapabilityApi: (Vehicle, (PropertyCapability) -> Unit, (Exception) -> Unit) -> Unit =
    { _, _, failed ->
        failed(UnsupportedOperationException())
    }

/** Decimal text keeps a Float's value as written instead of its binary expansion. */
private fun contractBound(value: Number?): Double? = value?.toString()?.toDouble()

/**
 * A value in the form the contract's `rawValue` allows. A non-finite float is not a value JSON can
 * carry, so it counts as no value.
 */
fun contractValue(raw: Any?): Any? =
    when (raw) {
        is Float -> raw.toString().toDouble().takeIf { it.isFinite() }
        is Double -> raw.takeIf { it.isFinite() }
        is Enum<*> -> raw.name
        is SmartGlassConfig -> SmartGlassConfigValue(raw.zoneCount, raw.levelCount)
        else -> raw
    }

private fun doorLabel(area: DoorArea) = area.values.joinToString("+") { it.name }

private fun seatLabel(area: SeatArea) = area.values.joinToString("+") { it.name }

private fun windowLabel(area: WindowArea) = area.values.joinToString("+") { it.name }

private fun mirrorLabel(area: MirrorArea) = area.values.joinToString("+") { it.name }

private fun wheelLabel(area: WheelArea) = area.values.joinToString("+") { it.name }

private fun parts(area: String?) = requireNotNull(area).split('+')

private fun doorArea(area: String?) = DoorArea(*parts(area).map(DoorValue::valueOf).toTypedArray())

private fun seatArea(area: String?) = SeatArea(*parts(area).map(SeatValue::valueOf).toTypedArray())

private fun windowArea(area: String?) =
    WindowArea(*parts(area).map(WindowValue::valueOf).toTypedArray())

private fun mirrorArea(area: String?) =
    MirrorArea(*parts(area).map(MirrorValue::valueOf).toTypedArray())

private fun wheelArea(area: String?) =
    WheelArea(*parts(area).map(WheelValue::valueOf).toTypedArray())

/** Every catalog property, spelled out so the compiler checks each SDK signature. */
val SDK_PROPERTIES: Map<String, SdkProperty> =
    mapOf(
        "BRAKE_PEDAL_STATUS" to
            SdkProperty(
                { v, done, failed ->
                    v.getBrake().checkBrakePedalStatusCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getBrake().getCurrentBrakePedalStatus(value, failed) },
            ),
        "TEMPERATURE_DISPLAY_UNIT" to
            SdkProperty(
                { v, done, failed ->
                    v.getDisplay().checkTemperatureDisplayUnitCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getDisplay().getCurrentTemperatureDisplayUnit(value, failed)
                },
            ),
        "DOOR_LOCK" to
            SdkProperty(
                { v, done, failed ->
                    v.getDoor().checkDoorLockCapabilities(areaList(done, ::doorLabel), failed)
                },
                { v, a, value, failed -> v.getDoor().getDoorLockState(doorArea(a), value, failed) },
            ),
        "DOOR_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getDoor().checkDoorPositionCapabilities(areaBounds(done, ::doorLabel), failed)
                },
                { v, a, value, failed ->
                    v.getDoor().getCurrentDoorPosition(doorArea(a), value, failed)
                },
            ),
        "CRUISE_CONTROL_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getDrivingMode().checkCruiseControlStateCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getDrivingMode().getCruiseControlState(value, failed) },
            ),
        "CRUISE_CONTROL_TARGET_SPEED" to
            SdkProperty(
                { v, done, failed ->
                    v.getDrivingMode()
                        .checkCruiseControlTargetSpeedCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getDrivingMode().getCruiseControlTargetSpeed(value, failed)
                },
            ),
        "DRIVING_MODE_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getDrivingMode().checkDrivingModeStateCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getDrivingMode().getCurrentDrivingModeState(value, failed)
                },
            ),
        "EV_BATTERY_PERCENT" to
            SdkProperty(
                { v, done, failed ->
                    v.getEvBattery()
                        .checkEvBatteryStateOfChargeCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getEvBattery().getEvBatteryStateOfCharge(value, failed)
                },
            ),
        "FUEL_PERCENT" to
            SdkProperty(
                { v, done, failed ->
                    v.getFuel().checkFuelPercentCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getFuel().getFuelPercent(value, failed) },
            ),
        "CUMULATIVE_EV_EFFICIENCY" to
            SdkProperty(
                { v, done, failed ->
                    v.getEvBattery()
                        .checkCumulativeEvEfficiencyCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getEvBattery().getCumulativeEvEfficiency(value, failed)
                },
            ),
        "EV_CHARGE_CURRENT_DRAW_LIMIT" to
            SdkProperty(
                { v, done, failed ->
                    v.getEvBattery()
                        .checkEvChargeCurrentDrawLimitCapabilities(globalList(done), failed)
                },
                { v, _, value, failed ->
                    v.getEvBattery().getEvChargeCurrentDrawLimit(value, failed)
                },
            ),
        "EV_CHARGE_SWITCH_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getEvBattery().checkEvChargeSwitchCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getEvBattery().getEvChargeSwitchState(value, failed) },
            ),
        "EV_EFFICIENCY" to
            SdkProperty(
                { v, done, failed ->
                    v.getEvBattery().checkCurrentEvEfficiencyCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getEvBattery().getCurrentEvEfficiency(value, failed) },
            ),
        "INSTANTANEOUS_EV_EFFICIENCY" to
            SdkProperty(
                { v, done, failed ->
                    v.getEvBattery()
                        .checkInstantaneousEvEfficiencyCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getEvBattery().getInstantaneousEvEfficiency(value, failed)
                },
            ),
        "SINCE_CHARGE_EV_EFFICIENCY" to
            SdkProperty(
                { v, done, failed ->
                    v.getEvBattery()
                        .checkSinceChargeEvEfficiencyCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getEvBattery().getSinceChargeEvEfficiency(value, failed)
                },
            ),
        "CUMULATIVE_FUEL_ECONOMY" to
            SdkProperty(
                { v, done, failed ->
                    v.getFuel().checkCumulativeFuelEconomyCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getFuel().getCumulativeFuelEconomy(value, failed) },
            ),
        "FUEL_ECONOMY" to
            SdkProperty(
                { v, done, failed ->
                    v.getFuel().checkCurrentFuelEconomyCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getFuel().getCurrentFuelEconomy(value, failed) },
            ),
        "INSTANTANEOUS_FUEL_ECONOMY" to
            SdkProperty(
                { v, done, failed ->
                    v.getFuel().checkInstantaneousFuelEconomyCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getFuel().getInstantaneousFuelEconomy(value, failed) },
            ),
        "SINCE_CHARGE_FUEL_ECONOMY" to
            SdkProperty(
                { v, done, failed ->
                    v.getFuel().checkSinceChargeFuelEconomyCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getFuel().getSinceChargeFuelEconomy(value, failed) },
            ),
        "GLOVE_BOX_LOCK_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getDoor().checkGloveBoxLockCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getDoor().getCurrentGloveBoxLockState(value, failed) },
            ),
        "HVAC_AC_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC().checkHvacAcCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed -> v.getHVAC().getHvacAcState(seatArea(a), value, failed) },
            ),
        "HVAC_AIR_CLEANER_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC().checkHvacAirCleanerCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getHVAC().getHvacAirCleanerState(seatArea(a), value, failed)
                },
            ),
        "HVAC_AUTO_RECIRCULATION_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC()
                        .checkHvacAutoRecirculationCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getHVAC().getHvacAutoRecirculationState(seatArea(a), value, failed)
                },
            ),
        "HVAC_AUTO_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC().checkHvacAutoCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed -> v.getHVAC().getHvacAutoState(seatArea(a), value, failed) },
            ),
        "HVAC_DEFROSTER_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC()
                        .checkHvacDefrosterCapabilities(areaList(done, ::windowLabel), failed)
                },
                { v, a, value, failed ->
                    v.getHVAC().getHvacDefrosterState(windowArea(a), value, failed)
                },
            ),
        "HVAC_DUAL_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC().checkHvacDualCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed -> v.getHVAC().getHvacDualState(seatArea(a), value, failed) },
            ),
        "HVAC_FAN_DIRECTION" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC()
                        .checkHvacFanDirectionCapabilities(areaValues(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getHVAC().getHvacFanDirection(seatArea(a), value, failed)
                },
            ),
        "HVAC_FAN_SPEED" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC().checkHvacFanSpeedCapabilities(areaBounds(done, ::seatLabel), failed)
                },
                { v, a, value, failed -> v.getHVAC().getHvacFanSpeed(seatArea(a), value, failed) },
            ),
        "HVAC_MAX_AC_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC().checkHvacMaxAcCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getHVAC().getHvacMaxAcState(seatArea(a), value, failed)
                },
            ),
        "HVAC_MAX_DEFROST_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC().checkHvacMaxDefrostCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getHVAC().getHvacMaxDefrostState(seatArea(a), value, failed)
                },
            ),
        "HVAC_POWER_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC().checkHvacPowerCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getHVAC().getHvacPowerState(seatArea(a), value, failed)
                },
            ),
        "HVAC_RECIRCULATION_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC()
                        .checkHvacRecirculationCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getHVAC().getHvacRecirculationState(seatArea(a), value, failed)
                },
            ),
        "HVAC_TEMPERATURE" to
            SdkProperty(
                { v, done, failed ->
                    val bounds = areaBounds<SeatArea, Float>(done, ::seatLabel)
                    v.getHVAC()
                        .checkHvacTemperatureCapabilities(
                            {
                                config: CapabilityConfig,
                                areas: Map<SeatArea, AreaBounds<Float>>,
                                _: HvacTemperatureConfigArray? ->
                                bounds(config, areas)
                            },
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getHVAC()
                        .getHvacTemperature(seatArea(a), TemperatureUnit.CELSIUS, value, failed)
                },
            ),
        "HVAC_TEMPERATURE_CURRENT" to
            SdkProperty(
                { v, done, failed ->
                    v.getHVAC()
                        .checkHvacTemperatureCurrentCapabilities(
                            areaList(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getHVAC().getHvacTemperatureCurrent(seatArea(a), value, failed)
                },
            ),
        "AMBIENT_COLOR_AUTO_BY_DRIVE_MODE_ENABLED" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight()
                        .checkAmbientColorAutoByDriveModeEnabledCapabilities(
                            configOnly(done),
                            failed,
                        )
                },
                { v, _, value, failed ->
                    v.getLight().getAmbientColorAutoByDriveModeEnabled(value, failed)
                },
            ),
        "AMBIENT_LIGHT_BRIGHTNESS" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight()
                        .checkAmbientLightBrightnessCapabilities(
                            areaBounds(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getLight().getAmbientLightBrightness(seatArea(a), value, failed)
                },
            ),
        "AMBIENT_LIGHT_BRIGHTNESS_COLOR" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight()
                        .checkAmbientLightBrightnessColorCapabilities(
                            areaList(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getLight().getAmbientLightBrightnessColor(seatArea(a), value, failed)
                },
            ),
        "AMBIENT_LIGHT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight()
                        .checkAmbientLightStateCapabilities(areaValues(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getLight().getAmbientLightState(seatArea(a), value, failed)
                },
            ),
        "AMBIENT_LIGHT_SWITCH" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight()
                        .checkAmbientLightSwitchCapabilities(areaValues(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getLight().getAmbientLightSwitch(seatArea(a), value, failed)
                },
            ),
        "CABIN_LIGHT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkCabinLightStateCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getCurrentCabinLightState(value, failed) },
            ),
        "CABIN_LIGHT_SWITCH" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkCabinLightSwitchCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getCabinLightSwitch(value, failed) },
            ),
        "FRONT_FOG_LIGHT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkFrontFogLightStateCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getCurrentFrontFogLightState(value, failed) },
            ),
        "FRONT_FOG_LIGHT_SWITCH" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkFrontFogLightSwitchCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getFrontFogLightSwitch(value, failed) },
            ),
        "HIGH_BEAM_LIGHT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkHighBeamLightStateCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getCurrentHighBeamLightState(value, failed) },
            ),
        "HIGH_BEAM_LIGHT_SWITCH" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkHighBeamLightSwitchCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getHighBeamLightSwitch(value, failed) },
            ),
        "LOW_BEAM_LIGHT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkLowBeamLightStateCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getCurrentLowBeamLightState(value, failed) },
            ),
        "LOW_BEAM_LIGHT_SWITCH" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkLowBeamLightSwitchCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getLowBeamLightSwitch(value, failed) },
            ),
        "READING_LIGHT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight()
                        .checkReadingLightStateCapabilities(areaValues(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getLight().getCurrentReadingLightState(seatArea(a), value, failed)
                },
            ),
        "READING_LIGHT_SWITCH" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight()
                        .checkReadingLightSwitchCapabilities(areaValues(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getLight().getReadingLightSwitch(seatArea(a), value, failed)
                },
            ),
        "REAR_FOG_LIGHT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkRearFogLightStateCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getCurrentRearFogLightState(value, failed) },
            ),
        "REAR_FOG_LIGHT_SWITCH" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkRearFogLightSwitchCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getRearFogLightSwitch(value, failed) },
            ),
        "TAIL_LAMP_LIGHT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkTailLampLightStateCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getCurrentTailLampLightState(value, failed) },
            ),
        "TAIL_LAMP_LIGHT_SWITCH" to
            SdkProperty(
                { v, done, failed ->
                    v.getLight().checkTailLampLightSwitchCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getLight().getTailLampLightSwitch(value, failed) },
            ),
        "BRAKE_FLUID_LEVEL_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getSafety().checkBrakeFluidLevelStateCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getSafety().getBrakeFluidLevelState(value, failed) },
            ),
        "ENGINE_OIL_LEVEL_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getSafety().checkEngineOilLevelStateCapabilities(globalList(done), failed)
                },
                { v, _, value, failed -> v.getSafety().getEngineOilLevelState(value, failed) },
            ),
        "LOW_WASHER_FLUID_WARNING" to
            SdkProperty(
                { v, done, failed ->
                    v.getSafety()
                        .checkLowWasherFluidWarningStateCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getSafety().hasLowWasherFluidWarning(value, failed) },
            ),
        "POWER_DOWN_WARNING_INDICATOR" to
            SdkProperty(
                { v, done, failed ->
                    v.getSafety()
                        .checkPowerDownWarningIndicatorCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getSafety().hasPowerDownWarningIndicator(value, failed)
                },
            ),
        "SEAT_AIRBAG_BEEN_DEPLOYED" to
            SdkProperty(
                { v, done, failed ->
                    v.getSafety()
                        .checkAirbagDeployedCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSafety().hasSeatAirbagBeenDeployed(seatArea(a), value, failed)
                },
            ),
        "SEAT_AIRBAG_ENABLED" to
            SdkProperty(
                { v, done, failed ->
                    v.getSafety().checkAirbagCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSafety().hasSeatAirbagEnabled(seatArea(a), value, failed)
                },
            ),
        "SEAT_BELT_NOT_BUCKLED_WARNING" to
            SdkProperty(
                { v, done, failed ->
                    v.getSafety()
                        .checkSeatNotBuckledWarningCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSafety().hasSeatBeltNotBuckledWarning(seatArea(a), value, failed)
                },
            ),
        "TRANSMISSION_OIL_LEVEL_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getSafety()
                        .checkTransmissionOilLevelStateCapabilities(globalList(done), failed)
                },
                { v, _, value, failed ->
                    v.getSafety().getTransmissionOilLevelState(value, failed)
                },
            ),
        "HVAC_SEAT_TEMPERATURE" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatTemperatureCapabilities(areaBounds(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSeat().getHvacSeatTemperature(seatArea(a), value, failed)
                },
            ),
        "HVAC_SEAT_VENTILATION_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatVentilationCapabilities(areaBounds(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSeat().getHvacSeatVentilationState(seatArea(a), value, failed)
                },
            ),
        "SEAT_BACKREST_ANGLE_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatBackrestAnglePositionCapabilities(
                            areaBounds(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatBackrestAnglePosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_BELT_BUCKLED_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatBeltBuckledCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatBeltBuckledState(seatArea(a), value, failed)
                },
            ),
        "SEAT_BELT_HEIGHT_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatBeltHeightPositionCapabilities(
                            areaBounds(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatBeltHeightPosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_DEPTH_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatDepthPositionCapabilities(areaBounds(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatDepthPosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_FORE_AFT_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatForeAftPositionCapabilities(areaBounds(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatForeAftPosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_HEADREST_ANGLE_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatHeadrestAnglePositionCapabilities(
                            areaBounds(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatHeadrestAnglePosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_HEADREST_FORE_AFT_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatHeadrestForeAftPositionCapabilities(
                            areaBounds(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatHeadrestForeAftPosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_HEADREST_HEIGHT_AFT_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatHeadrestHeightPositionCapabilities(
                            areaBounds(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatHeadrestHeightAftPosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_HEIGHT_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatHeightPositionCapabilities(areaBounds(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatHeightPosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_LUMBAR_FORE_AFT_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatLumbarForeAftPositionCapabilities(
                            areaBounds(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatLumbarForeAftPosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_LUMBAR_SIDE_SUPPORT_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatLumbarSideSupportPositionCapabilities(
                            areaBounds(done, ::seatLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatLumbarSideSupportPosition(seatArea(a), value, failed)
                },
            ),
        "SEAT_OCCUPANCY" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat().checkSeatOccupancyCapabilities(areaList(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatOccupancy(seatArea(a), value, failed)
                },
            ),
        "SEAT_TILT_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getSeat()
                        .checkSeatTiltPositionCapabilities(areaBounds(done, ::seatLabel), failed)
                },
                { v, a, value, failed ->
                    v.getSeat().getCurrentSeatTiltPosition(seatArea(a), value, failed)
                },
            ),
        "HVAC_SIDE_MIRROR_HEAT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getSideMirror()
                        .checkHvacSideMirrorHeatCapabilities(
                            areaBounds(done, ::mirrorLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getSideMirror().getHvacSideMirrorHeatState(mirrorArea(a), value, failed)
                },
            ),
        "HVAC_STEERING_WHEEL_HEAT_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getSteeringWheel()
                        .checkHvacSteeringWheelHeatCapabilities(globalBounds(done), failed)
                },
                { v, _, value, failed ->
                    v.getSteeringWheel().getHvacSteeringWheelHeatState(value, failed)
                },
            ),
        "STEERING_WHEEL_ANGLE" to
            SdkProperty(
                { v, done, failed ->
                    v.getSteeringWheel()
                        .checkSteeringWheelAngleCapabilities(globalBounds(done), failed)
                },
                { v, _, value, failed ->
                    v.getSteeringWheel().getCurrentSteeringWheelAngle(value, failed)
                },
            ),
        "LOW_TIRE_PRESSURE" to
            SdkProperty(
                { v, done, failed ->
                    v.getTire()
                        .checkLowTirePressureCapabilities(areaList(done, ::wheelLabel), failed)
                },
                { v, a, value, failed ->
                    v.getTire().getLowTirePressure(wheelArea(a), value, failed)
                },
            ),
        "ETCS_VOICE_GUIDANCE_VOLUME" to
            SdkProperty(
                { v, done, failed ->
                    v.getToll().checkEtcsVoiceGuidanceVolumeCapabilities(globalBounds(done), failed)
                },
                { v, _, value, failed ->
                    v.getToll().getCurrentEtcsVoiceGuidanceVolume(value, failed)
                },
            ),
        "DISTANCE_DRIVEN" to
            SdkProperty(
                { v, done, failed ->
                    v.getOdometer().checkCurrentDistanceDrivenCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getOdometer().getCurrentDistanceDriven(value, failed) },
            ),
        "TRIP_CUMULATIVE_DRIVING_DISTANCE" to
            SdkProperty(
                { v, done, failed ->
                    v.getOdometer()
                        .checkTripCumulativeDrivingDistanceCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getOdometer().getTripCumulativeDrivingDistance(value, failed)
                },
            ),
        "TRIP_CUMULATIVE_DRIVING_TIME" to
            SdkProperty(
                { v, done, failed ->
                    v.getOdometer()
                        .checkTripCumulativeDrivingTimeCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getOdometer().getTripCumulativeDrivingTime(value, failed)
                },
            ),
        "TRIP_CURRENT_DRIVING_DISTANCE" to
            SdkProperty(
                { v, done, failed ->
                    v.getOdometer()
                        .checkTripCurrentDrivingDistanceCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getOdometer().getTripCurrentDrivingDistance(value, failed)
                },
            ),
        "TRIP_CURRENT_DRIVING_TIME" to
            SdkProperty(
                { v, done, failed ->
                    v.getOdometer()
                        .checkTripCurrentDrivingTimeCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getOdometer().getTripCurrentDrivingTime(value, failed) },
            ),
        "TRIP_SINCE_REFILL_DRIVING_DISTANCE" to
            SdkProperty(
                { v, done, failed ->
                    v.getOdometer()
                        .checkTripSinceRefillDrivingDistanceCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getOdometer().getTripSinceRefillDrivingDistance(value, failed)
                },
            ),
        "TRIP_SINCE_REFILL_DRIVING_TIME" to
            SdkProperty(
                { v, done, failed ->
                    v.getOdometer()
                        .checkTripSinceRefillDrivingTimeCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getOdometer().getTripSinceRefillDrivingTime(value, failed)
                },
            ),
        "TURN_SIGNAL_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getTurnSignal().checkTurnSignalStateCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed ->
                    v.getTurnSignal().getCurrentTurnSignalState(value, failed)
                },
            ),
        "SMART_GLASS_CONFIG" to
            SdkProperty(
                noCapabilityApi,
                { v, a, value, failed ->
                    v.getWindow().getSmartGlassConfig(windowArea(a), value, failed)
                },
            ),
        "SMART_GLASS_LEVEL" to
            SdkProperty(
                { v, done, failed ->
                    v.getWindow()
                        .checkSmartGlassLevelCapabilities(areaBounds(done, ::windowLabel), failed)
                },
                { v, a, value, failed ->
                    v.getWindow().getCurrentSmartGlassLevel(windowArea(a), value, failed)
                },
            ),
        "SMART_GLASS_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getWindow().checkSmartGlassStateCapabilities(configOnly(done), failed)
                },
                { v, _, value, failed -> v.getWindow().getCurrentSmartGlassState(value, failed) },
            ),
        "WINDOW_BLIND_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getWindow()
                        .checkWindowBlindPositionCapabilities(
                            areaBounds(done, ::windowLabel),
                            failed,
                        )
                },
                { v, a, value, failed ->
                    v.getWindow().getCurrentWindowBlindPosition(windowArea(a), value, failed)
                },
            ),
        "WINDOW_LOCK_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getWindow().checkWindowLockCapabilities(areaList(done, ::windowLabel), failed)
                },
                { v, a, value, failed ->
                    v.getWindow().getCurrentWindowLockState(windowArea(a), value, failed)
                },
            ),
        "WINDOW_POSITION" to
            SdkProperty(
                { v, done, failed ->
                    v.getWindow()
                        .checkWindowPositionCapabilities(areaBounds(done, ::windowLabel), failed)
                },
                { v, a, value, failed ->
                    v.getWindow().getCurrentWindowPosition(windowArea(a), value, failed)
                },
            ),
        "WIPER_STATE" to
            SdkProperty(
                { v, done, failed ->
                    v.getWiper()
                        .checkWiperStateCapabilities(areaValues(done, ::windowLabel), failed)
                },
                { v, a, value, failed ->
                    v.getWiper().getCurrentWiperState(windowArea(a), value, failed)
                },
            ),
        "WIPER_SWITCH" to
            SdkProperty(
                { v, done, failed ->
                    v.getWiper()
                        .checkWiperSwitchCapabilities(areaValues(done, ::windowLabel), failed)
                },
                { v, a, value, failed ->
                    v.getWiper().getCurrentWiperSwitch(windowArea(a), value, failed)
                },
            ),
    )
