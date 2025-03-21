package com.unciv.logic.city

import com.unciv.logic.automation.ConstructionAutomation
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unique.StateForConditionals
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.civilopedia.CivilopediaCategories
import com.unciv.ui.civilopedia.FormattedLine
import com.unciv.ui.utils.Fonts
import com.unciv.ui.utils.withItem
import com.unciv.ui.utils.withoutItem
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * City constructions manager.
 *
 * @property cityInfo the city it refers to
 * @property currentConstructionFromQueue name of the construction that is currently being produced
 * @property currentConstructionIsUserSet a flag indicating if the [currentConstructionFromQueue] has been set by the user or by the AI
 * @property constructionQueue a list of constructions names enqueued
 */
class CityConstructions {
    @Transient
    lateinit var cityInfo: CityInfo

    @Transient
    private var builtBuildingObjects = ArrayList<Building>()

    @Transient
    val builtBuildingUniqueMap = UniqueMap()

    var builtBuildings = HashSet<String>()
    val inProgressConstructions = HashMap<String, Int>()
    var currentConstructionFromQueue: String
        get() {
            return if (constructionQueue.isEmpty()) "" 
                else constructionQueue.first()
        }
        set(value) {
            if (constructionQueue.isEmpty()) constructionQueue.add(value) else constructionQueue[0] = value
        }
    var currentConstructionIsUserSet = false
    var constructionQueue = mutableListOf<String>()
    var productionOverflow = 0
    val queueMaxSize = 10

    // Maps cities to the buildings they received
    val freeBuildingsProvidedFromThisCity: HashMap<String, HashSet<String>> = hashMapOf()
    
    //region pure functions
    fun clone(): CityConstructions {
        val toReturn = CityConstructions()
        toReturn.builtBuildings.addAll(builtBuildings)
        toReturn.inProgressConstructions.putAll(inProgressConstructions)
        toReturn.currentConstructionIsUserSet = currentConstructionIsUserSet
        toReturn.constructionQueue.addAll(constructionQueue)
        toReturn.productionOverflow = productionOverflow
        toReturn.freeBuildingsProvidedFromThisCity.putAll(freeBuildingsProvidedFromThisCity)
        return toReturn
    }

    internal fun getBuildableBuildings(): Sequence<Building> = cityInfo.getRuleset().buildings.values
        .asSequence().filter { it.isBuildable(this) }

    fun getConstructableUnits() = cityInfo.getRuleset().units.values
        .asSequence().filter { it.isBuildable(this) }

    fun getBasicStatBuildings(stat: Stat) = cityInfo.getRuleset().buildings.values
        .asSequence()
        .filter { !it.isAnyWonder() && it.replaces == null && it.getStats(null)[stat] > 0f }

    /**
     * @return [Stats] provided by all built buildings in city plus the bonus from Library
     */
    fun getStats(): Stats {
        val stats = Stats()
        for (building in getBuiltBuildings())
            stats.add(building.getStats(cityInfo))
        return stats
    }

    /**
     * @return Maintenance cost of all built buildings
     */
    fun getMaintenanceCosts(): Int {
        var maintenanceCost = 0
        val freeBuildings = cityInfo.civInfo.civConstructions.getFreeBuildings(cityInfo.id)
        
        for (building in getBuiltBuildings()) {
            if (building.name !in freeBuildings) {
                maintenanceCost += building.maintenance
            }
        }
        
        return maintenanceCost
    }

    /**
     * @return Bonus (%) [Stats] provided by all built buildings in city
     */
    fun getStatPercentBonuses(): Stats {
        val stats = Stats()
        for (building in getBuiltBuildings())
            stats.add(building.getStatPercentageBonuses(cityInfo))
        return stats
    }

    fun getCityProductionTextForCityButton(): String {
        val currentConstructionSnapshot = currentConstructionFromQueue // See below
        var result = currentConstructionSnapshot.tr()
        if (currentConstructionSnapshot != "") {
            val construction = PerpetualConstruction.perpetualConstructionsMap[currentConstructionSnapshot]
            if (construction == null) result += getTurnsToConstructionString(currentConstructionSnapshot)
            else result += construction.getProductionTooltip(cityInfo)
        }
        return result
    }
    
    fun addFreeBuildings() {
        // "Gain a free [buildingName] [cityFilter]"
        val freeBuildingUniques = cityInfo.getLocalMatchingUniques(UniqueType.GainFreeBuildings, StateForConditionals(cityInfo.civInfo, cityInfo))

        for (unique in freeBuildingUniques) {
            val freeBuildingName = cityInfo.civInfo.getEquivalentBuilding(unique.params[0]).name
            val citiesThatApply = when (unique.params[1]) {
                "in this city" -> listOf(cityInfo)
                "in other cities" -> cityInfo.civInfo.cities.filter { it !== cityInfo }
                else -> cityInfo.civInfo.cities.filter { it.matchesFilter(unique.params[1]) }
            }
            
            for (city in citiesThatApply) {
                if (city.cityConstructions.containsBuildingOrEquivalent(freeBuildingName)) continue
                city.cityConstructions.addBuilding(freeBuildingName)
                if (city.id !in freeBuildingsProvidedFromThisCity)
                    freeBuildingsProvidedFromThisCity[city.id] = hashSetOf()

                freeBuildingsProvidedFromThisCity[city.id]!!.add(freeBuildingName)
            }
        }

        // Civ-level uniques - for these only add free buildings from each city to itself to avoid weirdness on city conquest
        for (unique in cityInfo.civInfo.getMatchingUniques(UniqueType.GainFreeBuildings, stateForConditionals = StateForConditionals(cityInfo.civInfo, cityInfo))) {
            val freeBuildingName = cityInfo.civInfo.getEquivalentBuilding(unique.params[0]).name
            if (cityInfo.matchesFilter(unique.params[1])) {
                if (cityInfo.id !in freeBuildingsProvidedFromThisCity)
                    freeBuildingsProvidedFromThisCity[cityInfo.id] = hashSetOf()
                freeBuildingsProvidedFromThisCity[cityInfo.id]!!.add(freeBuildingName)
                if (!isBuilt(freeBuildingName))
                    addBuilding(freeBuildingName)
            }
        }
    }

    /** @constructionName needs to be a non-perpetual construction, else an empty string is returned */
    internal fun getTurnsToConstructionString(constructionName: String, useStoredProduction:Boolean = true): String {
        val construction = getConstruction(constructionName)
        if (construction !is INonPerpetualConstruction) return ""   // shouldn't happen
        val cost = construction.getProductionCost(cityInfo.civInfo)
        val turnsToConstruction = turnsToConstruction(constructionName, useStoredProduction)
        val currentProgress = if (useStoredProduction) getWorkDone(constructionName) else 0
        val lines = ArrayList<String>()
        val buildable = construction.uniqueObjects.none{ it.isOfType(UniqueType.Unbuildable) }
        if (buildable)
            lines += (if (currentProgress == 0) "" else "$currentProgress/") +
                    "$cost${Fonts.production} $turnsToConstruction${Fonts.turn}"
        val otherStats = Stat.values().filter {
            (it != Stat.Gold || !buildable) &&  // Don't show rush cost for consistency
            construction.canBePurchasedWithStat(cityInfo, it)
        }.joinToString(" / ") { "${construction.getStatBuyCost(cityInfo, it)}${it.character}" }
        if (otherStats.isNotEmpty()) lines += otherStats
        return lines.joinToString("\n", "\n")
    }

    fun getProductionMarkup(ruleset: Ruleset): FormattedLine {
        val currentConstructionSnapshot = currentConstructionFromQueue
        if (currentConstructionSnapshot.isEmpty()) return FormattedLine()
        val category = when {
            ruleset.buildings[currentConstructionSnapshot]?.isAnyWonder() == true ->
                CivilopediaCategories.Wonder.name
            currentConstructionSnapshot in ruleset.buildings ->
                CivilopediaCategories.Building.name
            currentConstructionSnapshot in ruleset.units ->
                CivilopediaCategories.Unit.name
            else -> ""
        }
        var label = "{$currentConstructionSnapshot}"
        if (!PerpetualConstruction.perpetualConstructionsMap.containsKey(currentConstructionSnapshot)) {
            val turnsLeft = turnsToConstruction(currentConstructionSnapshot)
            label += " - $turnsLeft${Fonts.turn}"
        }
        return if (category.isEmpty()) FormattedLine(label)
            else FormattedLine(label, link="$category/$currentConstructionSnapshot")
    }

    fun getCurrentConstruction(): IConstruction = getConstruction(currentConstructionFromQueue)

    fun isBuilt(buildingName: String): Boolean = builtBuildings.contains(buildingName)
    fun isBeingConstructed(constructionName: String): Boolean = currentConstructionFromQueue == constructionName
    fun isEnqueued(constructionName: String): Boolean = constructionQueue.contains(constructionName)
    fun isBeingConstructedOrEnqueued(constructionName: String): Boolean = isBeingConstructed(constructionName) || isEnqueued(constructionName)

    fun isQueueFull(): Boolean = constructionQueue.size == queueMaxSize

    fun isBuildingWonder(): Boolean {
        val currentConstruction = getCurrentConstruction()
        return currentConstruction is Building && currentConstruction.isWonder
    }

    /** If the city is constructing multiple units of the same type, subsequent units will require the full cost  */
    fun isFirstConstructionOfItsKind(constructionQueueIndex: Int, name: String): Boolean {
        // if the construction name is the same as the current construction, it isn't the first
        return constructionQueueIndex == constructionQueue.indexOfFirst { it == name }
    }
    

    internal fun getConstruction(constructionName: String): IConstruction {
        val gameBasics = cityInfo.getRuleset()
        when {
            constructionName == "" -> return getConstruction("Nothing")
            gameBasics.buildings.containsKey(constructionName) -> return gameBasics.buildings[constructionName]!!
            gameBasics.units.containsKey(constructionName) -> return gameBasics.units[constructionName]!!
            else -> {
                val special = PerpetualConstruction.perpetualConstructionsMap[constructionName]
                if (special != null) return special
            }
        }

        class NotBuildingOrUnitException(message: String) : Exception(message)
        throw NotBuildingOrUnitException("$constructionName is not a building or a unit!")
    }

    internal fun getBuiltBuildings(): Sequence<Building> = builtBuildingObjects.asSequence()

    fun containsBuildingOrEquivalent(building: String): Boolean =
            isBuilt(building) || getBuiltBuildings().any { it.replaces == building }

    fun getWorkDone(constructionName: String): Int {
        return if (inProgressConstructions.containsKey(constructionName)) inProgressConstructions[constructionName]!!
            else 0
    }

    fun getRemainingWork(constructionName: String, useStoredProduction: Boolean = true): Int {
        val constr = getConstruction(constructionName)
        return when {
            constr is PerpetualConstruction -> 0
            useStoredProduction -> (constr as INonPerpetualConstruction).getProductionCost(cityInfo.civInfo) - getWorkDone(constructionName)
            else -> (constr as INonPerpetualConstruction).getProductionCost(cityInfo.civInfo)
        }
    }

    fun turnsToConstruction(constructionName: String, useStoredProduction: Boolean = true): Int {
        val workLeft = getRemainingWork(constructionName, useStoredProduction)
        if (workLeft < 0) // This most often happens when a production is more than finished in a multiplayer game while its not your turn
            return 0 // So we finish it at the start of the next turn. This could technically also happen when we lower production costs during our turn,
        // but distinguishing those two cases is difficult, and the second one is much rarer than the first
        if (workLeft <= productionOverflow) // if we already have stored up enough production to finish it directly
            return 1 // we'll finish this next turn

        val cityStatsForConstruction: Stats
        if (currentConstructionFromQueue == constructionName) cityStatsForConstruction = cityInfo.cityStats.currentCityStats
        else {
            /*
            The ol' Switcharoo - what would our stats be if that was our current construction?
            Since this is only ever used for UI purposes, I feel fine with having it be a bit inefficient
            and recalculating the entire city stats
            We don't want to change our current construction queue - what if we have an empty queue,
             this can affect the city if we run it on another thread like in ConstructionsTable -
            So we run the numbers for the other construction
            ALSO apparently if we run on the actual cityStats from another thread,
              we get all sorts of fun concurrency problems when accessing various parts of the cityStats.
            SO, we create an entirely new CityStats and iterate there - problem solve!
            */
            val cityStats = CityStats(cityInfo)
            cityStats.statsFromTiles = cityInfo.cityStats.statsFromTiles // take as-is
            val construction = cityInfo.cityConstructions.getConstruction(constructionName)
            cityStats.update(construction, false)
            cityStatsForConstruction = cityStats.currentCityStats
        }

        val production = cityStatsForConstruction.production.roundToInt()

        return ceil((workLeft-productionOverflow) / production.toDouble()).toInt()
    }
    //endregion

    //region state changing functions
    fun setTransients() {
        builtBuildingObjects = ArrayList(builtBuildings.map {
            cityInfo.getRuleset().buildings[it]
                    ?: throw java.lang.Exception("Building $it is not found!")
        })
        updateUniques()
    }

    fun addProductionPoints(productionToAdd: Int) {
        val construction = getConstruction(currentConstructionFromQueue)
        if (construction is PerpetualConstruction) {
            productionOverflow += productionToAdd
            return
        }
        if (!inProgressConstructions.containsKey(currentConstructionFromQueue))
            inProgressConstructions[currentConstructionFromQueue] = 0
        inProgressConstructions[currentConstructionFromQueue] = inProgressConstructions[currentConstructionFromQueue]!! + productionToAdd
    }

    fun constructIfEnough() {
        validateConstructionQueue()

        // Update InProgressConstructions for any available refunds
        validateInProgressConstructions()

        val construction = getConstruction(currentConstructionFromQueue)
        if (construction is PerpetualConstruction) chooseNextConstruction() // check every turn if we could be doing something better, because this doesn't end by itself
        else {
            val productionCost = (construction as INonPerpetualConstruction).getProductionCost(cityInfo.civInfo)
            if (inProgressConstructions.containsKey(currentConstructionFromQueue)
                    && inProgressConstructions[currentConstructionFromQueue]!! >= productionCost) {
                productionOverflow = inProgressConstructions[currentConstructionFromQueue]!! - productionCost
                // See the URL below for explanation for this cap
                // https://forums.civfanatics.com/threads/hammer-overflow.419352/
                val maxOverflow = maxOf(productionCost, cityInfo.cityStats.currentCityStats.production.roundToInt())
                if (productionOverflow > maxOverflow)
                    productionOverflow = maxOverflow
                constructionComplete(construction)
            }
        }
    }

    fun endTurn(cityStats: Stats) {
        validateConstructionQueue()
        validateInProgressConstructions()

        if (getConstruction(currentConstructionFromQueue) !is PerpetualConstruction) {
            if (getWorkDone(currentConstructionFromQueue) == 0) {
                constructionBegun(getConstruction(currentConstructionFromQueue))
            }
            addProductionPoints(cityStats.production.roundToInt() + productionOverflow)
            productionOverflow = 0
        }
    }


    private fun validateConstructionQueue() {
        val queueSnapshot = constructionQueue.toMutableList()
        constructionQueue.clear()

        for (constructionName in queueSnapshot) {
            if (getConstruction(constructionName).isBuildable(this))
                constructionQueue.add(constructionName)
        }
    }

    private fun validateInProgressConstructions() {
        // remove obsolete stuff from in progress constructions - happens often and leaves clutter in memory and save files
        // should have little visible consequences - any accumulated points that may be reused later should stay (nukes when manhattan project city lost, nat wonder when conquered an empty city...), all other points should be refunded
        // Should at least be called before each turn - if another civ completes a wonder after our previous turn, we should get the refund this turn
        val inProgressSnapshot = inProgressConstructions.keys.filter { it != currentConstructionFromQueue }
        for (constructionName in inProgressSnapshot) {
            val construction = getConstruction(constructionName)
            // Perpetual constructions should always still be valid (I hope)
            if (construction is PerpetualConstruction) continue
            
            val rejectionReasons = 
                (construction as INonPerpetualConstruction).getRejectionReasons(this)

            if (rejectionReasons.hasAReasonToBeRemovedFromQueue()) {
                if (construction is Building) {
                    // Production put into wonders gets refunded
                    if (construction.isWonder && getWorkDone(constructionName) != 0) {
                        cityInfo.civInfo.addGold( getWorkDone(constructionName) )
                        val buildingIcon = "BuildingIcons/${constructionName}"
                        cityInfo.civInfo.addNotification("Excess production for [$constructionName] converted to [${getWorkDone(constructionName)}] gold", NotificationIcon.Gold, buildingIcon)
                    }
                } else if (construction is BaseUnit) {
                    // Production put into upgradable units gets put into upgraded version
                    if (rejectionReasons.all { it == RejectionReason.Obsoleted } && construction.upgradesTo != null) {
                        // I'd love to use the '+=' operator but since 'inProgressConstructions[...]' can be null, kotlin doesn't allow me to
                        if (!inProgressConstructions.contains(construction.upgradesTo)) {
                            inProgressConstructions[construction.upgradesTo!!] = getWorkDone(constructionName)
                        } else {
                            inProgressConstructions[construction.upgradesTo!!] = inProgressConstructions[construction.upgradesTo!!]!! + getWorkDone(constructionName)
                        }
                    }
                }
                inProgressConstructions.remove(constructionName)
            }
        }
    }

    private fun constructionBegun(construction: IConstruction) {
        if (construction !is Building) return;
        if (construction.uniqueObjects.none { it.placeholderText == "Triggers a global alert upon build start" }) return
        val buildingIcon = "BuildingIcons/${construction.name}"
        for (otherCiv in cityInfo.civInfo.gameInfo.civilizations) {
            if (otherCiv == cityInfo.civInfo) continue
            when {
                (otherCiv.exploredTiles.contains(cityInfo.location) && otherCiv != cityInfo.civInfo) ->
                    otherCiv.addNotification("The city of [${cityInfo.name}] has started constructing [${construction.name}]!",
                        cityInfo.location, NotificationIcon.Construction, buildingIcon)
                (otherCiv.knows(cityInfo.civInfo)) ->
                    otherCiv.addNotification("[${cityInfo.civInfo.civName}] has started constructing [${construction.name}]!",
                        NotificationIcon.Construction, buildingIcon)
                else -> otherCiv.addNotification("An unknown civilization has started constructing [${construction.name}]!",
                    NotificationIcon.Construction, buildingIcon)
            }
        }
    }

    private fun constructionComplete(construction: INonPerpetualConstruction) {
        construction.postBuildEvent(this)
        if (construction.name in inProgressConstructions)
            inProgressConstructions.remove(construction.name)
        if (construction.name == currentConstructionFromQueue)
            removeCurrentConstruction()

        validateConstructionQueue() // if we've build e.g. the Great Lighthouse, then Lighthouse is no longer relevant in the queue

        val buildingIcon = "BuildingIcons/${construction.name}"
        if (construction is Building && construction.isWonder) {
            cityInfo.civInfo.popupAlerts.add(PopupAlert(AlertType.WonderBuilt, construction.name))
            for (civ in cityInfo.civInfo.gameInfo.civilizations) {
                if (civ.exploredTiles.contains(cityInfo.location))
                    civ.addNotification("[${construction.name}] has been built in [${cityInfo.name}]",
                            cityInfo.location, NotificationIcon.Construction, buildingIcon)
                else
                    civ.addNotification("[${construction.name}] has been built in a faraway land", buildingIcon)
            }
        } else {
            val icon = if (construction is Building) buildingIcon else construction.name // could be a unit, in which case take the unit name.
            cityInfo.civInfo.addNotification("[${construction.name}] has been built in [" + cityInfo.name + "]",
                    cityInfo.location, NotificationIcon.Construction, icon)
        }
        if (construction is Building && construction.uniqueObjects.any { it.placeholderText == "Triggers a global alert upon completion" } ) {
            for (otherCiv in cityInfo.civInfo.gameInfo.civilizations) {
                // No need to notify ourself, since we already got the building notification anyway
                if (otherCiv == cityInfo.civInfo) continue
                val completingCivDescription =
                    if (otherCiv.knows(cityInfo.civInfo)) "[${cityInfo.civInfo.civName}]" else "An unknown civilization"
                otherCiv.addNotification("$completingCivDescription has completed [${construction.name}]!",
                    NotificationIcon.Construction, buildingIcon)
            }
        }
    }

    fun addBuilding(buildingName: String) {
        val buildingObject = cityInfo.getRuleset().buildings[buildingName]!!
        builtBuildingObjects = builtBuildingObjects.withItem(buildingObject)
        builtBuildings.add(buildingName)
        updateUniques()
    }

    fun removeBuilding(buildingName: String) {
        val buildingObject = cityInfo.getRuleset().buildings[buildingName]!!
        builtBuildingObjects = builtBuildingObjects.withoutItem(buildingObject)
        builtBuildings.remove(buildingName)
        updateUniques()
    }

    fun updateUniques() {
        builtBuildingUniqueMap.clear()
        for (building in getBuiltBuildings())
            for (unique in building.uniqueObjects)
                builtBuildingUniqueMap.addUnique(unique)
    }

    /**
     *  Purchase a construction for gold
     *  called from NextTurnAutomation and the City UI
     *  Build / place the new item, deduct cost, and maintain queue.
     *
     *  @param constructionName What to buy (needed since buying something not queued is allowed)
     *  @param queuePosition    Position in the queue or -1 if not from queue
     *                          Note: -1 does not guarantee queue will remain unchanged (validation)
     *  @param automatic        Flag whether automation should try to choose what next to build (not coming from UI)
     *                          Note: settings.autoAssignCityProduction is handled later
     *  @param stat             Stat object of the stat with which was paid for the construction
     *  @return                 Success (false e.g. unit cannot be placed
     */
    fun purchaseConstruction(
        constructionName: String, 
        queuePosition: Int, 
        automatic: Boolean, 
        stat: Stat = Stat.Gold
    ): Boolean {
        if (!(getConstruction(constructionName) as INonPerpetualConstruction).postBuildEvent(this, stat))
            return false // nothing built - no pay

        if (!cityInfo.civInfo.gameInfo.gameParameters.godMode) {
            val construction = getConstruction(constructionName)
            if (construction is PerpetualConstruction) return false
            val constructionCost = (construction as INonPerpetualConstruction).getStatBuyCost(cityInfo, stat)
            if (constructionCost == null) return false // We should never end up here anyway, so things have already gone _way_ wrong
            cityInfo.addStat(stat, -1 * constructionCost)

            val conditionalState = StateForConditionals(civInfo = cityInfo.civInfo, cityInfo = cityInfo)
            
            if ((
                    cityInfo.civInfo.getMatchingUniques(UniqueType.BuyUnitsIncreasingCost, conditionalState) +
                    cityInfo.civInfo.getMatchingUniques(UniqueType.BuyBuildingsIncreasingCost, conditionalState)
                ).any {
                    (
                        construction is BaseUnit && construction.matchesFilter(it.params[0]) ||
                        construction is Building && construction.matchesFilter(it.params[0])
                    )
                    && cityInfo.matchesFilter(it.params[3])
                    && it.params[2] == stat.name
                }
            ) {
                cityInfo.civInfo.civConstructions.boughtItemsWithIncreasingPrice.add(constructionName, 1)
            }
        }

        if (queuePosition in 0 until constructionQueue.size)
            removeFromQueue(queuePosition, automatic)
        validateConstructionQueue()

        return true
    }
    
    fun hasBuildableStatBuildings(stat: Stat): Boolean {
        return getBasicStatBuildings(stat)
            .map { cityInfo.civInfo.getEquivalentBuilding(it.name) }
            .filter { it.isBuildable(this) || isBeingConstructedOrEnqueued(it.name) }
            .any()
    }

    fun addCheapestBuildableStatBuilding(stat: Stat): String? {
        val cheapestBuildableStatBuilding = getBasicStatBuildings(stat)
            .map { cityInfo.civInfo.getEquivalentBuilding(it.name) }
            .filter { it.isBuildable(this) || isBeingConstructedOrEnqueued(it.name) }
            .minByOrNull { it.cost }?.name

        if (cheapestBuildableStatBuilding == null)
            return null

        constructionComplete(getConstruction(cheapestBuildableStatBuilding) as INonPerpetualConstruction)

        return cheapestBuildableStatBuilding
    }

    private fun removeCurrentConstruction() = removeFromQueue(0, true)

    fun chooseNextConstruction() {
        validateConstructionQueue()
        if (constructionQueue.isNotEmpty()) {
            if (currentConstructionFromQueue != ""
                    // If the USER set a perpetual construction, then keep it!
                    && (getConstruction(currentConstructionFromQueue) !is PerpetualConstruction || currentConstructionIsUserSet)) return
        }

        ConstructionAutomation(this).chooseNextConstruction()
    }

    fun addToQueue(constructionName: String) {
        if (isQueueFull()) return
        val construction = getConstruction(constructionName)
        if (!construction.isBuildable(this)) return
        if (construction is Building && isBeingConstructedOrEnqueued(constructionName)) return
        if (currentConstructionFromQueue == "" || currentConstructionFromQueue == "Nothing") {
            currentConstructionFromQueue = constructionName
        } else if (getConstruction(constructionQueue.last()) is PerpetualConstruction) {
            if (construction is PerpetualConstruction) {  // perpetual constructions will replace each other
                constructionQueue.removeAt(constructionQueue.size - 1)
                constructionQueue.add(constructionName)
            } else
                constructionQueue.add(constructionQueue.size - 1, constructionName) // insert new construction before perpetual one
        } else
            constructionQueue.add(constructionName)
        currentConstructionIsUserSet = true
    }

    /** If this was done automatically, we should automatically try to choose a new construction and treat it as such */
    fun removeFromQueue(constructionQueueIndex: Int, automatic: Boolean) {
        val constructionName = constructionQueue.removeAt(constructionQueueIndex)
        val construction = getConstruction(constructionName)
        if (construction is Building) {
            val improvement = construction.getImprovement(cityInfo.getRuleset())
            if (improvement != null) {
                val tileWithImprovement = cityInfo.getTiles().firstOrNull { it.improvementInProgress == improvement.name }
                tileWithImprovement?.improvementInProgress = null
                tileWithImprovement?.turnsToImprovement = 0
            }
        }

        if (constructionQueue.isEmpty()) {
            if (automatic) chooseNextConstruction()
            else constructionQueue.add("Nothing") // To prevent Construction Automation
            currentConstructionIsUserSet = false
        } else currentConstructionIsUserSet = true // we're just continuing the regular queue
    }

    fun raisePriority(constructionQueueIndex: Int) {
        constructionQueue.swap(constructionQueueIndex - 1, constructionQueueIndex)
    }

    // Lowering == Highering next element in queue
    fun lowerPriority(constructionQueueIndex: Int) {
        raisePriority(constructionQueueIndex + 1)
    }

    //endregion
    private fun MutableList<String>.swap(idx1: Int, idx2: Int) {
        val tmp = this[idx1]
        this[idx1] = this[idx2]
        this[idx2] = tmp
    }
}
