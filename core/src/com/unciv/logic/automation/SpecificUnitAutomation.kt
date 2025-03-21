﻿package com.unciv.logic.automation

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.CityInfo
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.civilization.ReligionManager
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.MapUnit
import com.unciv.logic.map.TileInfo
import com.unciv.logic.map.TileMap
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.worldscreen.unit.UnitActions
import kotlin.math.max
import kotlin.math.min

object SpecificUnitAutomation {

    private fun hasWorkableSeaResource(tileInfo: TileInfo, civInfo: CivilizationInfo): Boolean =
            tileInfo.isWater && tileInfo.improvement == null && tileInfo.hasViewableResource(civInfo)

    fun automateWorkBoats(unit: MapUnit) {
        val closestReachableResource = unit.civInfo.cities.asSequence()
                .flatMap { city -> city.getWorkableTiles() }
                .filter {
                    hasWorkableSeaResource(it, unit.civInfo)
                            && (unit.currentTile == it || unit.movement.canMoveTo(it))
                }
                .sortedBy { it.aerialDistanceTo(unit.currentTile) }
                .firstOrNull { unit.movement.canReach(it) }

        when (closestReachableResource) {
            null -> UnitAutomation.tryExplore(unit)
            else -> {
                unit.movement.headTowards(closestReachableResource)

                // could be either fishing boats or oil well
                val improvement = closestReachableResource.tileResource.improvement
                if (unit.currentTile == closestReachableResource && improvement != null)
                    UnitActions.getWaterImprovementAction(unit)?.action?.invoke()
            }
        }
    }

    fun automateGreatGeneral(unit: MapUnit) {
        //try to follow nearby units. Do not garrison in city if possible
        val militaryUnitTilesInDistance = unit.movement.getDistanceToTiles().asSequence()
                .filter {
                    val militant = it.key.militaryUnit
                    militant != null && militant.civInfo == unit.civInfo
                            && (it.key.civilianUnit == null || it.key.civilianUnit == unit)
                            && militant.getMaxMovement() <= 2 && !it.key.isCityCenter()
                }

        val maxAffectedTroopsTile = militaryUnitTilesInDistance
                .maxByOrNull {
                    it.key.getTilesInDistance(2).count { tile ->
                        val militaryUnit = tile.militaryUnit
                        militaryUnit != null && militaryUnit.civInfo == unit.civInfo
                    }
                }?.key
        if (maxAffectedTroopsTile != null) {
            unit.movement.headTowards(maxAffectedTroopsTile)
            return
        }

        // try to revenge and capture their tiles
        val enemyCities = unit.civInfo.getKnownCivs()
                .filter { unit.civInfo.getDiplomacyManager(it).hasModifier(DiplomaticModifiers.StealingTerritory) }
                .flatMap { it.cities }.asSequence()
        // find the suitable tiles (or their neighbours)
        val tileToSteal = enemyCities.flatMap { it.getTiles() } // City tiles
                .filter { it.neighbors.any { tile -> tile.getOwner() != unit.civInfo } } // Edge city tiles
                .flatMap { it.neighbors.asSequence() } // Neighbors of edge city tiles
                .filter {
                    it in unit.civInfo.viewableTiles // we can see them
                            && it.neighbors.any { tile -> tile.getOwner() == unit.civInfo }// they are close to our borders
                }
                .sortedBy {
                    // get closest tiles
                    val distance = it.aerialDistanceTo(unit.currentTile)
                    // ...also get priorities to steal the most valuable for them
                    val owner = it.getOwner()
                    if (owner != null)
                        distance - WorkerAutomation.getPriority(it, owner)
                    else distance
                }
                .firstOrNull { unit.movement.canReach(it) } // canReach is performance-heavy and always a last resort
        // if there is a good tile to steal - go there
        if (tileToSteal != null) {
            unit.movement.headTowards(tileToSteal)
            if (unit.currentMovement > 0 && unit.currentTile == tileToSteal)
                UnitActions.getImprovementConstructionActions(unit, unit.currentTile).firstOrNull()?.action?.invoke()
            return
        }

        // try to build a citadel for defensive purposes
        if (WorkerAutomation.evaluateFortPlacement(unit.currentTile, unit.civInfo, true)) {
            UnitActions.getImprovementConstructionActions(unit, unit.currentTile).firstOrNull()?.action?.invoke()
            return
        }

        //if no unit to follow, take refuge in city or build citadel there.
        val reachableTest: (TileInfo) -> Boolean = {
            it.civilianUnit == null &&
                    unit.movement.canMoveTo(it)
                    && unit.movement.canReach(it)
        }
        val cityToGarrison = unit.civInfo.cities.asSequence().map { it.getCenterTile() }
                .sortedBy { it.aerialDistanceTo(unit.currentTile) }
                .firstOrNull { reachableTest(it) }

        if (cityToGarrison != null) {
            // try to find a good place for citadel nearby
            val potentialTilesNearCity = cityToGarrison.getTilesInDistanceRange(3..4)
            val tileForCitadel = potentialTilesNearCity.firstOrNull {
                reachableTest(it) &&
                        WorkerAutomation.evaluateFortPlacement(it, unit.civInfo, true)
            }
            if (tileForCitadel != null) {
                unit.movement.headTowards(tileForCitadel)
                if (unit.currentMovement > 0 && unit.currentTile == tileForCitadel)
                    UnitActions.getImprovementConstructionActions(unit, unit.currentTile).firstOrNull()?.action?.invoke()
            } else
                unit.movement.headTowards(cityToGarrison)
            return
        }
    }

    private fun rankTileAsCityCenter(tileInfo: TileInfo, nearbyTileRankings: Map<TileInfo, Float>,
                                     luxuryResourcesInCivArea: Sequence<TileResource>): Float {
        val bestTilesFromOuterLayer = tileInfo.getTilesAtDistance(2)
                .sortedByDescending { nearbyTileRankings[it] }.take(2)
        val top5Tiles = (tileInfo.neighbors + bestTilesFromOuterLayer)
                .sortedByDescending { nearbyTileRankings[it] }
                .take(5)
        var rank = top5Tiles.map { nearbyTileRankings.getValue(it) }.sum()
        if (tileInfo.isCoastalTile()) rank += 5

        val luxuryResourcesInCityArea = tileInfo.getTilesAtDistance(2).filter { it.resource != null }
                .map { it.tileResource }.filter { it.resourceType == ResourceType.Luxury }.distinct()
        val luxuryResourcesAlreadyInCivArea = luxuryResourcesInCivArea.map { it.name }.toHashSet()
        val luxuryResourcesNotYetInCiv = luxuryResourcesInCityArea
                .count { it.name !in luxuryResourcesAlreadyInCivArea }
        rank += luxuryResourcesNotYetInCiv * 10

        return rank
    }

    fun automateSettlerActions(unit: MapUnit) {
        if (unit.getTile().militaryUnit == null     // Don't move until you're accompanied by a military unit
            && !unit.civInfo.isCityState()          // ..unless you're a city state that was unable to settle its city on turn 1
            && unit.getDamageFromTerrain() < unit.health) return    // Also make sure we won't die waiting

        val tilesNearCities = sequence {
            for (city in unit.civInfo.gameInfo.getCities()) {
                val center = city.getCenterTile()
                if (unit.civInfo.knows(city.civInfo) &&
                            // If the CITY OWNER knows that the UNIT OWNER agreed not to settle near them
                            city.civInfo.getDiplomacyManager(unit.civInfo).hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs)
                        ) {
                    yieldAll(center.getTilesInDistance(6))
                    continue
                }
                for (tile in center.getTilesAtDistance(3)) {
                    if (tile.getContinent() == center.getContinent())
                        yield(tile)
                }
                yieldAll(center.getTilesInDistance(2))
            }
        }.toSet()

        // This is to improve performance - instead of ranking each tile in the area up to 19 times, do it once.
        val nearbyTileRankings = unit.getTile().getTilesInDistance(7)
                .associateBy({ it }, { Automation.rankTile(it, unit.civInfo) })

        val distanceFromHome = if (unit.civInfo.cities.isEmpty()) 0
            else unit.civInfo.cities.minOf { it.getCenterTile().aerialDistanceTo(unit.getTile()) }
        val range = max(1, min(5, 8 - distanceFromHome)) // Restrict vision when far from home to avoid death marches

        val possibleCityLocations = unit.getTile().getTilesInDistance(range)
                .filter {
                    val tileOwner = it.getOwner()
                    it.isLand && !it.isImpassible() && (tileOwner == null || tileOwner == unit.civInfo) // don't allow settler to settle inside other civ's territory
                            && (unit.currentTile == it || unit.movement.canMoveTo(it))
                            && it !in tilesNearCities
                }.toList()

        val luxuryResourcesInCivArea = unit.civInfo.cities.asSequence()
                .flatMap { it.getTiles().asSequence() }.filter { it.resource != null }
                .map { it.tileResource }.filter { it.resourceType == ResourceType.Luxury }
                .distinct()

        if (unit.civInfo.gameInfo.turns == 0) {   // Special case, we want AI to settle in place on turn 1.
            val foundCityAction = UnitActions.getFoundCityAction(unit, unit.getTile())
            // Depending on era and difficulty we might start with more than one settler. In that case settle the one with the best location
            val otherSettlers = unit.civInfo.getCivUnits().filter { it.currentMovement > 0 && it.baseUnit == unit.baseUnit }
            if(foundCityAction?.action != null &&
                    otherSettlers.none {
                        rankTileAsCityCenter(it.getTile(), nearbyTileRankings, emptySequence()) > rankTileAsCityCenter(unit.getTile(), nearbyTileRankings, emptySequence())
                    } ) {
                foundCityAction.action.invoke()
                return
            }
        }

        val citiesByRanking = possibleCityLocations
                .map { Pair(it, rankTileAsCityCenter(it, nearbyTileRankings, luxuryResourcesInCivArea)) }
                .sortedByDescending { it.second }.toList()

        // It's possible that we'll see a tile "over the sea" that's better than the tiles close by, but that's not a reason to abandon the close tiles!
        // Also this lead to some routing problems, see https://github.com/yairm210/Unciv/issues/3653
        val bestCityLocation: TileInfo? = citiesByRanking.firstOrNull {
            val pathSize = unit.movement.getShortestPath(it.first).size
            return@firstOrNull pathSize in 1..3
        }?.first

        if (bestCityLocation == null) { // We got a badass over here, all tiles within 5 are taken?
            // Try to move towards the frontier
            val frontierCity = unit.civInfo.cities.maxByOrNull { it.getFrontierScore() }
            if (frontierCity != null && frontierCity.getFrontierScore() > 0  && unit.movement.canReach(frontierCity.getCenterTile()))
                unit.movement.headTowards(frontierCity.getCenterTile())
            if (UnitAutomation.tryExplore(unit)) return // try to find new areas
            UnitAutomation.wander(unit) // go around aimlessly
            return
        }

        val foundCityAction = UnitActions.getFoundCityAction(unit, bestCityLocation)
        if (foundCityAction?.action == null) { // this means either currentMove == 0 or city within 3 tiles
            if (unit.currentMovement > 0) // therefore, city within 3 tiles
                throw Exception("City within distance")
            return
        }

        unit.movement.headTowards(bestCityLocation)
        if (unit.getTile() == bestCityLocation && unit.currentMovement > 0)
            foundCityAction.action.invoke()
    }

    fun automateImprovementPlacer(unit: MapUnit) {
        val improvementName = unit.getMatchingUniques(UniqueType.ConstructImprovementConsumingUnit).first().params[0]
        val improvement = unit.civInfo.gameInfo.ruleSet.tileImprovements[improvementName]
            ?: return
        val relatedStat = improvement.maxByOrNull { it.value }?.key ?: Stat.Culture

        val citiesByStatBoost = unit.civInfo.cities.sortedByDescending {
            val stats = Stats()
            for (bonus in it.cityStats.statPercentBonusList.values) stats.add(bonus)
            stats[relatedStat]
        }


        for (city in citiesByStatBoost) {
            val applicableTiles = city.getWorkableTiles().filter {
                it.isLand && it.resource == null && !it.isCityCenter()
                        && (unit.currentTile == it || unit.movement.canMoveTo(it))
                        && !it.containsGreatImprovement()
            }
            if (applicableTiles.none()) continue

            val pathToCity = unit.movement.getShortestPath(city.getCenterTile())

            if (pathToCity.isEmpty()) continue
            if (pathToCity.size > 2) {
                if (unit.getTile().militaryUnit == null) return // Don't move until you're accompanied by a military unit
                unit.movement.headTowards(city.getCenterTile())
                return
            }

            // if we got here, we're pretty close, start looking!
            val chosenTile = applicableTiles.sortedByDescending { Automation.rankTile(it, unit.civInfo) }
                .firstOrNull { unit.movement.canReach(it) }
                ?: continue // to another city

            unit.movement.headTowards(chosenTile)
            if (unit.currentTile == chosenTile)
                UnitActions.getImprovementConstructionActions(unit, unit.currentTile).firstOrNull()?.action?.invoke()
            return
        }
    }

    fun automateMissionary(unit: MapUnit) {
        if (unit.religion != unit.civInfo.religionManager.religion?.name)
            return unit.destroy()

        val cities = unit.civInfo.gameInfo.getCities().asSequence()
            .filter { it.religion.getMajorityReligion()?.name != unit.getReligionDisplayName() }
            .filterNot { it.civInfo.isAtWarWith(unit.civInfo) }
            .minByOrNull { it.getCenterTile().aerialDistanceTo(unit.currentTile) } ?: return


        val destination = cities.getTiles().asSequence()
            .filterNot { unit.getTile().owningCity == it.owningCity } // to prevent the ai from moving around randomly
            .filter { unit.movement.canMoveTo(it) }
            .sortedBy { it.aerialDistanceTo(unit.currentTile) }
            .firstOrNull { unit.movement.canReach(it) } ?: return

        unit.movement.headTowards(destination)

        if (unit.currentTile.owningCity?.religion?.getMajorityReligion()?.name != unit.religion)
            doReligiousAction(unit, unit.getTile())
    }

    fun automateInquisitor(unit: MapUnit) {
        val cityToConvert = unit.civInfo.cities.asSequence()
            .filterNot { it.religion.getMajorityReligion()?.name == null }
            .filterNot { it.religion.getMajorityReligion()?.name == unit.religion }
            .minByOrNull { it.getCenterTile().aerialDistanceTo(unit.currentTile) }

        val cityToProtect = unit.civInfo.cities.asSequence()
            .filter { it.religion.getMajorityReligion()?.name == unit.religion }
            .filter { isInquisitorInTheCity(it, unit) }
            .maxByOrNull { it.population.population }  // cities with most populations will be prioritized by the AI

        var destination: TileInfo? = null

        if (cityToProtect != null) {
            destination = cityToProtect.getCenterTile().neighbors.asSequence()
                .filterNot { unit.getTile().owningCity == it.owningCity } // to prevent the ai from moving around randomly
                .filter { unit.movement.canMoveTo(it) }
                .sortedBy { it.aerialDistanceTo(unit.currentTile) }
                .firstOrNull { unit.movement.canReach(it) }
        }
        if (destination == null) {
            if (cityToConvert == null) return
            destination = cityToConvert.getCenterTile().neighbors.asSequence()
                .filterNot { unit.getTile().owningCity == it.owningCity } // to prevent the ai from moving around randomly
                .filter { unit.movement.canMoveTo(it) }
                .sortedBy { it.aerialDistanceTo(unit.currentTile) }
                .firstOrNull { unit.movement.canReach(it) }
        }

        if (destination == null) return

        unit.movement.headTowards(destination)


        if (cityToConvert != null && unit.currentTile.getCity() == destination!!.getCity()) {
            doReligiousAction(unit, destination)
        }

    }

    private fun isInquisitorInTheCity(city: CityInfo, unit: MapUnit): Boolean {
        if (!city.religion.isProtectedByInquisitor())
            return false

        for (tile in city.getCenterTile().neighbors)
            if (unit.currentTile == tile)
                return true
        return false
    }




    fun automateFighter(unit: MapUnit) {
        val tilesInRange = unit.currentTile.getTilesInDistance(unit.getRange())
        val enemyAirUnitsInRange = tilesInRange
                .flatMap { it.airUnits.asSequence() }.filter { it.civInfo.isAtWarWith(unit.civInfo) }

        if (enemyAirUnitsInRange.any()) return // we need to be on standby in case they attack
        if (BattleHelper.tryAttackNearbyEnemy(unit)) return

        if (tryRelocateToCitiesWithEnemyNearBy(unit)) return

        val pathsToCities = unit.movement.getAerialPathsToCities()
        if (pathsToCities.isEmpty()) return // can't actually move anywhere else

        val citiesByNearbyAirUnits = pathsToCities.keys
                .groupBy { key ->
                    key.getTilesInDistance(unit.getMaxMovementForAirUnits())
                            .count {
                                val firstAirUnit = it.airUnits.firstOrNull()
                                firstAirUnit != null && firstAirUnit.civInfo.isAtWarWith(unit.civInfo)
                            }
                }

        if (citiesByNearbyAirUnits.keys.any { it != 0 }) {
            val citiesWithMostNeedOfAirUnits = citiesByNearbyAirUnits.maxByOrNull { it.key }!!.value
            //todo: maybe group by size and choose highest priority within the same size turns
            val chosenCity = citiesWithMostNeedOfAirUnits.minByOrNull { pathsToCities.getValue(it).size }!! // city with min path = least turns to get there
            val firstStepInPath = pathsToCities.getValue(chosenCity).first()
            unit.movement.moveToTile(firstStepInPath)
            return
        }

        // no city needs fighters to defend, so let's attack stuff from the closest possible location
        tryMoveToCitiesToAerialAttackFrom(pathsToCities, unit)

    }

    fun automateBomber(unit: MapUnit) {
        if (BattleHelper.tryAttackNearbyEnemy(unit)) return

        if (tryRelocateToCitiesWithEnemyNearBy(unit)) return

        val pathsToCities = unit.movement.getAerialPathsToCities()
        if (pathsToCities.isEmpty()) return // can't actually move anywhere else
        tryMoveToCitiesToAerialAttackFrom(pathsToCities, unit)
    }

    private fun tryMoveToCitiesToAerialAttackFrom(pathsToCities: HashMap<TileInfo, ArrayList<TileInfo>>, airUnit: MapUnit) {
        val citiesThatCanAttackFrom = pathsToCities.keys
                .filter {
                    destinationCity -> destinationCity != airUnit.currentTile
                    && destinationCity.getTilesInDistance(airUnit.getRange())
                        .any { BattleHelper.containsAttackableEnemy(it, MapUnitCombatant(airUnit)) }
                }
        if (citiesThatCanAttackFrom.isEmpty()) return

        //todo: this logic looks similar to some parts of automateFighter, maybe pull out common code
        //todo: maybe group by size and choose highest priority within the same size turns
        val closestCityThatCanAttackFrom = citiesThatCanAttackFrom.minByOrNull { pathsToCities[it]!!.size }!!
        val firstStepInPath = pathsToCities[closestCityThatCanAttackFrom]!!.first()
        airUnit.movement.moveToTile(firstStepInPath)
    }
    
    fun automateNukes(unit: MapUnit) {
        val tilesInRange = unit.currentTile.getTilesInDistance(unit.getRange())
        for (tile in tilesInRange) {
            // For now AI will only use nukes against cities because in all honesty that's the best use for them.
            if (tile.isCityCenter() && tile.getOwner()!!.isAtWarWith(unit.civInfo) && Battle.mayUseNuke(MapUnitCombatant(unit), tile)) {
                Battle.NUKE(MapUnitCombatant(unit), tile)
                return
            }
        }
        tryRelocateToNearbyAttackableCities(unit)
    }

    // This really needs to be changed, to have better targeting for missiles
    fun automateMissile(unit: MapUnit) {
        if (BattleHelper.tryAttackNearbyEnemy(unit)) return
        tryRelocateToNearbyAttackableCities(unit)
    }
    
    private fun tryRelocateToNearbyAttackableCities(unit: MapUnit) {
        val tilesInRange = unit.currentTile.getTilesInDistance(unit.getRange())
        val immediatelyReachableCities = tilesInRange
                .filter { unit.movement.canMoveTo(it) }
        
        for (city in immediatelyReachableCities) {
            if (city.getTilesInDistance(unit.getRange())
                    .any { it.isCityCenter() && it.getOwner()!!.isAtWarWith(unit.civInfo) }) {
                unit.movement.moveToTile(city)
                return
            }
        }

        val pathsToCities = unit.movement.getAerialPathsToCities()
        if (pathsToCities.isEmpty()) return // can't actually move anywhere else
        tryMoveToCitiesToAerialAttackFrom(pathsToCities, unit)
    }

    private fun tryRelocateToCitiesWithEnemyNearBy(unit: MapUnit): Boolean {
        val immediatelyReachableCitiesAndCarriers = unit.currentTile
                .getTilesInDistance(unit.getMaxMovementForAirUnits()).filter { unit.movement.canMoveTo(it) }

        for (city in immediatelyReachableCitiesAndCarriers) {
            if (city.getTilesInDistance(unit.getRange())
                            .any { BattleHelper.containsAttackableEnemy(it, MapUnitCombatant(unit)) }) {
                unit.movement.moveToTile(city)
                return true
            }
        }
        return false
    }

    fun foundReligion(unit: MapUnit) {
        val cityToFoundReligionAt =
            if (unit.getTile().isCityCenter() && !unit.getTile().owningCity!!.isHolyCity()) unit.getTile().owningCity 
            else unit.civInfo.cities.firstOrNull {
                !it.isHolyCity()
                && unit.movement.canMoveTo(it.getCenterTile())
                && unit.movement.canReach(it.getCenterTile())
            }
        if (cityToFoundReligionAt == null) return
        if (unit.getTile() != cityToFoundReligionAt.getCenterTile()) {
            unit.movement.headTowards(cityToFoundReligionAt.getCenterTile())
            return
        }

        UnitActions.getFoundReligionAction(unit)()
    }
    
    fun enhanceReligion(unit: MapUnit) {
        // Try go to a nearby city
        if (!unit.getTile().isCityCenter())
            UnitAutomation.tryEnterOwnClosestCity(unit)
        
        // If we were unable to go there this turn, unable to do anything else
        if (!unit.getTile().isCityCenter())
            return
        
        UnitActions.getEnhanceReligionAction(unit)()
    }

    private fun doReligiousAction(unit: MapUnit, destination: TileInfo){
        val actionList: java.util.ArrayList<UnitAction> = ArrayList()
        UnitActions.addActionsWithLimitedUses(unit, actionList, destination)
        if (actionList.firstOrNull()?.action == null) return
        actionList.first().action!!.invoke()
    }
}
