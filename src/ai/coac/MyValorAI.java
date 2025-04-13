package ai.coac;

import ai.abstraction.AbstractionLayerAIWait1;
import ai.abstraction.pathfinding.AStarPathFinding;
import ai.core.AI;
import ai.core.ParameterSpecification;
import rts.*;
import rts.units.Unit;
import rts.units.UnitType;
import rts.units.UnitTypeTable;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MyValorAI
 * 
 * This AI is based on CoacAI with modifications incorporating an aggression parameter as well as
 * a simplified Nash Equilibrium decision mechanism.
 * 
 * - aggressionLevel: 0.0 means very cautious; 1.0 means very aggressive. (Default: 0.5)
 * - nashEquilibriumFactor: adjusts the sensitivity of the equilibrium decision. (Default: 1.0)
 * 
 * The Nash equilibrium decision is computed by comparing the aggregated "combat cost" scores of 
 * our units versus the enemy’s. These scores are weighted by both the aggressionLevel and the Nash 
 * equilibrium factor. This serves as a simplified game-theoretic decision rule for determining 
 * whether to attack using combat units.
 */
public class MyValorAI extends AbstractionLayerAIWait1 {
    
    // Aggression parameter: 0.0 = very cautious, 1.0 = very aggressive (default: 0.5)
    private double aggressionLevel = 0.5;
    
    // Nash Equilibrium Factor: adjusts the sensitivity of the equilibrium decision (default: 1.0)
    private double nashEquilibriumFactor = 1.0;
    
    // Getters and setters
    public void setAggressionLevel(double level) {   
        aggressionLevel = level;
    }
    
    public double getAggressionLevel() {
        return aggressionLevel;
    }
    
    public void setNashEquilibriumFactor(double factor) { 
        nashEquilibriumFactor = factor;
    }
    
    public double getNashEquilibriumFactor() {
        return nashEquilibriumFactor;
    }

    // Existing fields in the AI
    protected UnitTypeTable utt;
    UnitType workerType;
    UnitType baseType;
    UnitType barracksType;
    UnitType rangedType;
    UnitType heavyType;
    UnitType lightType;
    AStarPathFinding astar;
    Player p;
    Player enemyPlayer;

    List<Unit> resources;
    List<Unit> myClosestResources;
    List<Unit> enemyBases;
    List<Unit> allBases;
    List<Unit> myBases;
    List<Unit> myWorkers;
    List<Unit> myWorkersBusy;
    List<Unit> enemies;
    List<Unit> myUnits;
    List<Unit> aliveEnemies;
    List<Unit> myCombatUnits;
    List<Unit> enemyCombatUnits;
    List<Unit> myCombatUnitsBusy;
    List<Unit> myBarracks;
    List<Unit> enemyBarracks;
    List<Unit> myWorkersCombat;
    String map;
    private GameState gs;
    private PhysicalGameState pgs;
    private int defenseBaseDistance = 2;

    Map<Long, List<Integer>> baseDefensePositions;
    Map<Long, List<Integer>> baseDefensePositionsRanged;
    private Map<Long, Integer> damages; // enemyID -> damage taken
    private Map<Long, Long> harvesting; // workerID -> baseID
    private HashMap<Long, Boolean> constructingBarracksForBase; // baseID -> constructing
    private HashMap<Long, Boolean> baseSeparated; // baseID -> separated

    private boolean debug = false;
    private int resourceUsed;
    private boolean attackAll = false;
    private boolean attackWithCombat = false;
    private int MAXCYCLES;
    private boolean wasSeparated = false;

    public MyValorAI(UnitTypeTable a_utt) {
        this(a_utt, new AStarPathFinding());
    }

    public MyValorAI(UnitTypeTable a_utt, AStarPathFinding a_pf) {
        super(a_pf);
        harvesting = new HashMap<>();
        astar = a_pf;
        reset(a_utt);
    }

    @Override
    public void reset() {
        super.reset();
    }

    public void reset(UnitTypeTable a_utt) {
        utt = a_utt;
        workerType = utt.getUnitType("Worker");
        baseType = utt.getUnitType("Base");
        barracksType = utt.getUnitType("Barracks");
        rangedType = utt.getUnitType("Ranged");
        heavyType = utt.getUnitType("Heavy");
        lightType = utt.getUnitType("Light");
    }

    @Override
    public AI clone() {
        return new MyValorAI(utt, astar);
    }
    
    // ----------------------------------------------------------------------
    // Nash Equilibrium Decision Logic
    // ----------------------------------------------------------------------
    
    /**
     * Computes a decision based on a simplified Nash equilibrium idea.
     * In this heuristic, we aggregate the combat "cost" scores for our units and the enemy.
     * Then, we weight the difference using both the aggressionLevel and nashEquilibriumFactor.
     * 
     * @return true if the computed advantage suggests attacking, false otherwise.
     */
    private boolean computeNashEquilibriumDecision() {
        // Sum of enemy combat units cost
        int enemyCombatScore = enemyCombatUnits.stream().mapToInt(Unit::getCost).sum();
        // Sum our own combat units cost (free and busy)
        int myCombatScore = myCombatUnits.stream().mapToInt(Unit::getCost).sum()
                            + myCombatUnitsBusy.stream().mapToInt(Unit::getCost).sum();
        // Calculate our advantage – if positive, we are stronger
        int advantage = myCombatScore - enemyCombatScore;
        
        // A threshold scaled by nashEquilibriumFactor and (1.0 - aggressionLevel)
        // (The constant 10 is a scaling factor; adjust as needed)
        double threshold = nashEquilibriumFactor * (1.0 - aggressionLevel) * 10;
        
        return advantage > threshold;
        
        // Alternative: using a logistic function
        // double probability = 1.0 / (1.0 + Math.exp(-((double)advantage) / threshold));
        // return probability > 0.5;
    }
    
    // ----------------------------------------------------------------------
    // Additional Methods Added to Resolve Missing Definitions
    // ----------------------------------------------------------------------
    
    /**
     * Checks if the base is separated from enemies using a simple flood fill.
     * For larger maps (width >= 10), the method returns false to avoid heavy computation.
     */
    private boolean isBaseSeparated(Unit base) {
        if (pgs.getWidth() >= 10) {
            return false;
        }
        List<Position> stack = new LinkedList<>();
        List<Position> visited = new LinkedList<>();
        stack.add(new Position(base.getX(), base.getY()));
        while (!stack.isEmpty()) {
            Position pos = stack.remove(0);
            Unit unit = pgs.getUnitAt(pos.getX(), pos.getY());
            if (unit != null && isEnemyUnit(unit)) {
                return false;
            }
            List<Position> validAdjacentPos = pos.adjacentPos().stream()
                    .filter(this::isValidPos)
                    .filter(p -> !visited.contains(p))
                    .filter(p -> !stack.contains(p))
                    .filter(p -> {
                        if (pgs.getTerrain(p.getX(), p.getY()) == PhysicalGameState.TERRAIN_WALL) {
                            return false;
                        }
                        Unit unitAtPos = pgs.getUnitAt(p.getX(), p.getY());
                        return unitAtPos == null || !unitAtPos.getType().isResource;
                    })
                    .collect(Collectors.toList());
            stack.addAll(validAdjacentPos);
            visited.add(pos);
        }
        wasSeparated = true;
        return true;
    }
    
    /**
     * Computes barracks action for the 16x16 map.
     */
    private void computeBarracksActionBasesWorkers16x16A() {
        long heavyCount = myCombatUnits.stream().filter(u -> u.getType() == heavyType).count() 
                            + myCombatUnitsBusy.stream().filter(u -> u.getType() == heavyType).count();
        for (Unit barrack : myBarracks) {
            if (heavyCount > 0) {
                train(barrack, rangedType);
            } else {
                if (wasSeparated) {
                    train(barrack, rangedType);
                } else {
                    train(barrack, heavyType);
                }
            }
        }
    }
    
    /**
     * Computes the standard barracks action.
     */
    private void computeBarracksAction() {
        long heavyCount = myCombatUnits.stream().filter(u -> u.getType() == heavyType).count() 
                            + myCombatUnitsBusy.stream().filter(u -> u.getType() == heavyType).count();
        for (Unit barrack : myBarracks) {
            if (heavyCount > 3) {
                train(barrack, rangedType);
            } else {
                if (wasSeparated) {
                    train(barrack, rangedType);
                } else {
                    train(barrack, heavyType);
                }
            }
        }
    }
    
    // ----------------------------------------------------------------------
    // Main Initialization and Game Update Methods
    // ----------------------------------------------------------------------
    
    private void init() {
        if (pgs.getWidth() <= 8) {
            defenseBaseDistance = 2;
        } else if (pgs.getWidth() <= 16) {
            defenseBaseDistance = 3;
        } else if (pgs.getWidth() <= 24) {
            defenseBaseDistance = 4;
        } else if (pgs.getWidth() <= 32) {
            defenseBaseDistance = 5;
        } else if (pgs.getWidth() <= 64) {
            defenseBaseDistance = 7;
        }

        MAXCYCLES = 12000;
        if (pgs.getWidth() <= 8) {
            MAXCYCLES = 3000;
        } else if (pgs.getWidth() <= 16) {
            MAXCYCLES = 4000;
        } else if (pgs.getWidth() <= 24) {
            MAXCYCLES = 5000;
        } else if (pgs.getWidth() <= 32) {
            MAXCYCLES = 6000;
        } else if (pgs.getWidth() <= 64) {
            MAXCYCLES = 8000;
        }

        resourceUsed = 0;
        myWorkersCombat = new ArrayList<>();
        resources = new ArrayList<>();
        allBases = new ArrayList<>();
        enemyBases = new ArrayList<>();
        myBases = new ArrayList<>();
        myWorkers = new ArrayList<>();
        myWorkersBusy = new ArrayList<>();
        enemyCombatUnits = new ArrayList<>();
        myCombatUnits = new ArrayList<>();
        myCombatUnitsBusy = new ArrayList<>();
        myBarracks = new ArrayList<>();
        enemyBarracks = new ArrayList<>();
        enemies = new ArrayList<>();
        myUnits = new ArrayList<>();
        aliveEnemies = new ArrayList<>();

        for (Unit u : pgs.getUnits()) {
            if (u.getType().isResource) {
                resources.add(u);
            } else if (u.getType().isStockpile) {
                if (isEnemyUnit(u)) {
                    enemyBases.add(u);
                } else {
                    myBases.add(u);
                }
                allBases.add(u);
            } else if (u.getType().canHarvest && isAllyUnit(u)) {
                if (gs.getActionAssignment(u) == null) {
                    myWorkers.add(u);
                } else {
                    myWorkersBusy.add(u);
                }
            } else if (!u.getType().canHarvest && u.getType().canAttack) {
                if (isAllyUnit(u)) {
                    if (gs.getActionAssignment(u) == null) {
                        myCombatUnits.add(u);
                    } else {
                        myCombatUnitsBusy.add(u);
                    }
                } else {
                    enemyCombatUnits.add(u);
                }
            } else if (u.getType().equals(barracksType)) {
                if (isAllyUnit(u)) {
                    myBarracks.add(u);
                } else {
                    enemyBarracks.add(u);
                }
            }

            if (isEnemyUnit(u)) {
                enemies.add(u);
                aliveEnemies.add(u);
            } else {
                myUnits.add(u);
            }
        }

        myClosestResources = new ArrayList<>();
        for (Unit resource : resources) {
            Unit base = closestUnit(resource, allBases);
            if (base != null && isAllyUnit(base)) {
                myClosestResources.add(resource);
            }
        }

        baseDefensePositions = new HashMap<>();
        baseDefensePositionsRanged = new HashMap<>();

        // Set up defense positions for each base
        for (Unit base : myBases) {
            baseDefensePositions.put(base.getID(), getDefensePositions(base, defenseBaseDistance));
            baseDefensePositionsRanged.put(base.getID(), getDefensePositions(base, defenseBaseDistance - 1));
        }

        // Damages tracking for units already attacking
        damages = new HashMap<>();
        List<Unit> myUnitsBusyList = new LinkedList<>();
        myUnitsBusyList.addAll(myCombatUnitsBusy);
        myUnitsBusyList.addAll(myWorkersBusy);
        for (Unit unit : myUnitsBusyList) {
            UnitAction action = gs.getUnitAction(unit);
            printDebug(unit + " is doing " + action);
            if (action == null || action.getType() != UnitAction.TYPE_ATTACK_LOCATION) {
                continue;
            }
            Unit enemy = pgs.getUnitAt(action.getLocationX(), action.getLocationY());
            if (enemy == null) {
                if (debug) {
                    throw new RuntimeException("Problem: " + unit + " attacking empty cell " + action);
                }
                continue;
            }
            registerAttackDamage(unit, enemy);
        }

        // Update harvesting assignments
        List<Long> toBeRemoved = new ArrayList<>();
        List<Unit> myWorkersBusyAndFree = new LinkedList<>();
        myWorkersBusyAndFree.addAll(myWorkers);
        myWorkersBusyAndFree.addAll(myWorkersBusy);
        for (Map.Entry<Long, Long> entry : harvesting.entrySet()) {
            long harvesterID = entry.getKey();
            Optional<Unit> worker = myWorkersBusyAndFree.stream().filter(u -> u.getID() == harvesterID).findAny();
            if (!worker.isPresent()) {
                toBeRemoved.add(harvesterID);
                continue;
            }
            long baseID = entry.getValue();
            Optional<Unit> base = myBases.stream().filter(u -> u.getID() == baseID).findAny();
            if (!base.isPresent()) {
                toBeRemoved.add(harvesterID);
            }
        }
        for (long id : toBeRemoved) {
            harvesting.remove(id);
        }

        constructingBarracksForBase = new HashMap<>();
        // Check if a barracks is being constructed for a base
        for (Unit worker : myWorkersBusy) {
            UnitAction action = gs.getUnitAction(worker);
            if (action.getType() == UnitAction.TYPE_PRODUCE && action.getUnitType() == barracksType) {
                Unit base = closestUnit(worker, myBases);
                if (base != null) {
                    constructingBarracksForBase.put(base.getID(), true);
                }
            }
        }

        // Determine if bases are separated from enemies
        baseSeparated = new HashMap<>();
        for (Unit base : myBases) {
            baseSeparated.put(base.getID(), isBaseSeparated(base));
        }
    }

    private List<Integer> getDefensePositions(Unit base, int defenseBaseDistance) {
        List<Integer> defensePositions = new ArrayList<>();
        for (int x = 0; x < pgs.getWidth(); x++) {
            for (int y = 0; y < pgs.getHeight(); y++) {
                if (distanceChebyshev(base, x, y) != defenseBaseDistance) {
                    continue;
                }
                if (isThereResourceAdjacent(x, y, 1)) {
                    continue;
                }
                int pos = pgsPos(x, y);
                defensePositions.add(pos);
            }
        }
        return defensePositions;
    }
    
    // ----------------------------------------------------------------------
    // Main getAction
    // ----------------------------------------------------------------------
    
    @Override
    public PlayerAction getAction(int player, GameState gs) {
        long start_time = System.currentTimeMillis();
        this.gs = gs;
        this.pgs = gs.getPhysicalGameState();
        p = gs.getPlayer(player);
        enemyPlayer = gs.getPlayer(player == 0 ? 1 : 0);

        if (myWorkersCombat == null) { // On first call
            int hash = this.pgs.toString().hashCode();
            printDebug("Map hash: " + hash);
            if (hash == 1835166811) {
                this.map = "maps/16x16/basesWorkers16x16A.xml";
            } else {
                this.map = "";
            }
        }

        init();

        if (p.getResources() == 0 && resources.size() == 0) {
            attackAll = true;
            attackWithCombat = true;
        }
        
        // Use the Nash equilibrium decision to determine whether to attack with combat units
        attackWithCombat = computeNashEquilibriumDecision();
        int enemyCombatScore = enemyCombatUnits.stream().mapToInt(Unit::getCost).sum();
        int myCombatScore = myCombatUnits.stream().mapToInt(Unit::getCost).sum() +
                            myCombatUnitsBusy.stream().mapToInt(Unit::getCost).sum();
        printDebug("Combat scores: myCombatScore=" + myCombatScore +
                   ", enemyCombatScore=" + enemyCombatScore +
                   ", aggressionLevel=" + aggressionLevel +
                   ", Nash Factor=" + nashEquilibriumFactor +
                   ", decision (attackWithCombat)=" + attackWithCombat);

        this.computeWorkersAction();
        if (this.map.equals("maps/16x16/basesWorkers16x16A.xml")) {
            this.computeBarracksActionBasesWorkers16x16A();
        } else {
            this.computeBarracksAction();
        }
        this.computeBasesAction();
        this.computeCombatUnitsAction();

        printDebug("attackWithCombat " + attackWithCombat + " resources:" + p.getResources());

        long elapsedTime = System.currentTimeMillis() - start_time;
        printDebug(elapsedTime + "ms");
        if (TIME_BUDGET > 0 && elapsedTime >= TIME_BUDGET) {
            printDebug("TIMEOUT");
        }

        PlayerAction pa = translateActions(player, gs);
        printDebug("actions: " + pa);
        return pa;
    }
    
    // ----------------------------------------------------------------------
    // Combat Actions and Helpers
    // ----------------------------------------------------------------------
    
    private void computeCombatAction(Unit unit) {
        List<Unit> aliveEnemies = this.aliveEnemies;
        if (aliveEnemies.isEmpty()) {
            printDebug(unit + " no enemies, idling");
            actions.put(unit, new TrueIdle(unit));
            return;
        }

        List<Unit> enemiesInRange = aliveEnemies.stream()
                .filter(e -> enemyIsInRangeAttack(unit, e))
                .collect(Collectors.toList());
        List<Unit> attackableEnemies = enemiesInRange.stream()
                .filter(e -> !willBeMoveBeforeAttack(unit, e))
                .collect(Collectors.toList());

        if (!attackableEnemies.isEmpty()) {
            Unit closestEnemy = bestToAttack(unit, attackableEnemies);
            printDebug(unit + " attacking " + closestEnemy + " damageBeforeAttack:" +
                       damages.getOrDefault(closestEnemy.getID(), 0) +
                       " enemyAction:" + gs.getActionAssignment(closestEnemy));
            attackAndRegisterDamage(unit, closestEnemy);
            return;
        }

        if (!enemiesInRange.isEmpty()) {
            if (unit.getAttackRange() > 1) {
                Unit movingEnemy = closestUnitAfterMoveAction(unit, enemiesInRange);
                if (squareDist(unit, nextPos(movingEnemy, gs)) > movingEnemy.getAttackRange()) {
                    printDebug("ranged flee safe");
                    moveAwayFrom(unit, movingEnemy);
                    return;
                } else {
                    if (gs.getActionAssignment(movingEnemy).time - gs.getTime() +
                        movingEnemy.getAttackTime() >= unit.getMoveTime()) {
                        printDebug("ranged flee adjacent");
                        moveAwayFrom(unit, movingEnemy);
                    }
                }
            }
            printDebug(unit + " wait for moving unit");
            actions.put(unit, new TrueIdle(unit));
            return;
        }

        Unit closestEnemy = closestUnitAfterMoveAction(unit, aliveEnemies);

        if (attackWithCombat || distance(unit, closestEnemy) <= 5) {
            printDebug(unit + " chasing <=5 " + closestEnemy);
            chaseToAttack(unit, closestEnemy);
            return;
        }

        List<Unit> myUnitsList = new ArrayList<>();
        myUnitsList.addAll(myCombatUnits);
        myUnitsList.addAll(myCombatUnitsBusy);
        myUnitsList.addAll(myWorkersCombat);
        if (myUnitsList.isEmpty()) {
            if (gs.getTime() > MAXCYCLES / 2) {
                printDebug(unit + " chasing " + closestEnemy);
                chaseToAttack(unit, closestEnemy);
                return;
            }
            printDebug(unit + " waiting for more unit");
            actions.put(unit, new TrueIdle(unit));
            return;
        }

        Position centroid = centroid(myUnitsList);
        if (gs.getTime() > MAXCYCLES / 2 || distance(unit, centroid) <= 3) {
            printDebug(unit + " chasing " + closestEnemy);
            chaseToAttack(unit, closestEnemy);
        } else {
            printDebug(unit + " moving to centroid " + centroid);
            move(unit, centroid.getX(), centroid.getY());
        }
    }
    
    private void moveAwayFrom(Unit unit, Unit movingEnemy) {
        Position enemyPos = nextPos(movingEnemy, gs);
        int currentDist = distance(unit, enemyPos);

        Position newPos;
        Position posUp = new Position(unit.getX(), unit.getY() - 1);
        Position posRight = new Position(unit.getX() + 1, unit.getY());
        Position posLeft = new Position(unit.getX() - 1, unit.getY());
        Position posDown = new Position(unit.getX(), unit.getY() + 1);
        if (isValidPos(posUp) && isFreePos(posUp) && distance(enemyPos, posUp) > currentDist) {
            newPos = posUp;
        } else if (isValidPos(posDown) && isFreePos(posDown) && distance(enemyPos, posDown) > currentDist) {
            newPos = posDown;
        } else if (isValidPos(posRight) && isFreePos(posRight) && distance(enemyPos, posRight) > currentDist) {
            newPos = posRight;
        } else if (isValidPos(posLeft) && isFreePos(posLeft) && distance(enemyPos, posLeft) > currentDist) {
            newPos = posLeft;
        } else {
            printDebug(unit + " can't flee, blocked, waiting");
            actions.put(unit, new TrueIdle(unit));
            return;
        }

        if (enemyPos.x == unit.getX()) {
            if (isValidPos(posUp) && isFreePos(posUp) && distance(enemyPos, posUp) > currentDist) {
                newPos = posUp;
            } else if (isValidPos(posDown) && isFreePos(posDown) && distance(enemyPos, posDown) > currentDist) {
                newPos = posDown;
            }
        } else if (enemyPos.y == unit.getY()) {
            if (isValidPos(posRight) && isFreePos(posRight) && distance(enemyPos, posRight) > currentDist) {
                newPos = posRight;
            } else if (isValidPos(posLeft) && isFreePos(posLeft) && distance(enemyPos, posLeft) > currentDist) {
                newPos = posLeft;
            }
        }
        printDebug(unit + " fleeing away from " + movingEnemy + " going to " + newPos);
        move(unit, newPos.x, newPos.y);
    }
    
    private int sumDistanceFromEnemy(Position pos) {
        return aliveEnemies.stream().mapToInt(e -> distance(pos, nextPos(e, gs))).sum();
    }
    
    private void computeCombatUnitsAction() {
        for (Unit unit : myCombatUnits) {
            computeCombatAction(unit);
        }
    }
    
    private void computeBasesAction() {
        for (Unit base : myBases) {
            if (baseSeparated.getOrDefault(base.getID(), false)) {
                continue;
            }
            if (pgs.getWidth() <= 8) {
                train(base, workerType);
                continue;
            }
        }
        
        final int workerPerBase = 2;
        int producingWorker = 0;
        long producingCount = myBases.stream().filter(b -> gs.getActionAssignment(b) != null).count();
        for (Unit base : myBases) {
            if (myWorkers.size() + myWorkersBusy.size() + producingWorker + producingCount >= workerPerBase * myBases.size() &&
                p.getResources() - resourceUsed < 15) {
                return;
            }
            train(base, workerType);
            producingWorker++;
        }
    }
    
    private void computeWorkersAction() {
        if (attackAll) {
            for (Unit worker : myWorkers) {
                computeCombatAction(worker);
            }
            return;
        }
        // Build barracks if needed
        buildBarracks();
        
        // Harvesting behavior
        if (!myWorkers.isEmpty() && !myBases.isEmpty() && !resources.isEmpty()) {
            for (int i = 0; i < myBases.size(); i++) {
                Optional<Unit> optionalUnit = myWorkers.stream().filter(w -> !harvesting.containsKey(w.getID()))
                        .min(Comparator.comparingInt(this::harvestScore));
                Unit harvesterWorker = optionalUnit.orElse(null);
                if (harvesterWorker == null || harvestScore(harvesterWorker) == Integer.MAX_VALUE) {
                    break;
                }
                harvestClosest(harvesterWorker);
            }
        }
        
        // If not harvesting, assign workers to combat/defense
        for (Unit worker : myWorkers) {
            boolean isHarvester = harvesting.containsKey(worker.getID());
            Unit closestEnemy = closestUnit(worker, this.aliveEnemies);
            if (isHarvester) {
                if (closestEnemy != null && distance(closestEnemy, worker) <= 2) {
                    harvesting.remove(worker.getID());
                    myWorkersCombat.add(worker);
                    computeCombatAction(worker);
                    continue;
                }
                long baseID = harvesting.get(worker.getID());
                Unit assignedBase = gs.getUnit(baseID);
                Unit closestResource = closestUnit(worker, resources);
                printDebug(worker + " new assigned harvest " + closestResource);
                actions.put(worker, new HarvestReturn(worker, closestResource, assignedBase, pf));
                continue;
            }
            myWorkersCombat.add(worker);
            computeCombatAction(worker);
        }
    }
    
    private void buildBarracks() {
        if (myBarracks.size() == 0 && p.getResources() - resourceUsed >= barracksType.cost + 1) {
            for (Unit base : myBases) {
                if (constructingBarracksForBase.containsKey(base.getID())) {
                    continue;
                }
                boolean safeDistanceToBuild = true;
                if (!baseSeparated.getOrDefault(base.getID(), false)) {
                    Unit enemy = closestUnit(base, this.aliveEnemies);
                    if (enemy != null) {
                        safeDistanceToBuild = (distance(enemy, base)) * workerType.moveTime > barracksType.produceTime;
                    }
                    if (p.getResources() > 10 && p.getResources() >= enemyPlayer.getResources()) {
                        safeDistanceToBuild = true;
                    }
                }
                if (!safeDistanceToBuild) {
                    continue;
                }
                Unit closestWorker = closestUnit(base, myWorkers);
                if (closestWorker == null) {
                    break;
                }
                Unit enemy = closestUnit(closestWorker, this.aliveEnemies);
                if (enemy != null && distance(closestWorker, enemy) <= 2) {
                    continue;
                }
                if (distance(closestWorker, base) > 2) {
                    continue;
                }
                Position workerPos = new Position(closestWorker.getX(), closestWorker.getY());
                List<Position> adjacentPos = workerPos.adjacentPos();
                Position bestPos = null;
                int closestDist = wasSeparated ? 9999999 : 0;
                for (Position pos : adjacentPos) {
                    if (!isValidPos(pos)) {
                        continue;
                    }
                    if (isThereResourceAdjacent(pos.getX(), pos.getY(), 1)) {
                        continue;
                    }
                    if (pgs.getUnitAt(pos.x, pos.y) != null) {
                        continue;
                    }
                    if (enemy == null) {
                        break;
                    }
                    int dist = distance(enemy, pos);
                    if ((wasSeparated && dist < closestDist) || (!wasSeparated && dist > closestDist)) {
                        bestPos = pos;
                        closestDist = dist;
                    }
                }
                if (bestPos == null) {
                    continue;
                }
                actions.put(closestWorker, new BuildModified(closestWorker, barracksType, bestPos.x, bestPos.y, pf));
                printDebug(closestWorker + " BUILDING at " + bestPos);
                myWorkers.remove(closestWorker);
                myWorkersBusy.add(closestWorker);
                resourceUsed += barracksType.cost;
            }
        }
    }
    
    private void printDebug(String string) {
        if (!debug) {
            return;
        }
        System.out.println(string);
    }
    
    private boolean isThereResourceAdjacent(int x, int y, int distance) {
        Collection<Unit> unitsAround = pgs.getUnitsAround(x, y, distance, distance);
        Optional<Unit> resource = unitsAround.stream().filter(unit -> unit.getType().isResource).findAny();
        return resource.isPresent();
    }
    
    private boolean isThereBaseAdjacent(int x, int y, int distance) {
        Collection<Unit> unitsAround = pgs.getUnitsAround(x, y, distance, distance);
        Optional<Unit> resource = unitsAround.stream().filter(unit -> unit.getType().isStockpile).findAny();
        return resource.isPresent();
    }
    
    private void chaseToAttack(Unit worker, Unit closestEnemy) {
        attackAndRegisterDamage(worker, closestEnemy);
    }
    
    private void attackAndRegisterDamage(Unit worker, Unit closestEnemy) {
        if (enemyIsInRangeAttack(worker, closestEnemy)) {
            registerAttackDamage(worker, closestEnemy);
        }
        actions.put(worker, new CoacAttack(worker, closestEnemy, pf));
    }
    
    private void registerAttackDamage(Unit worker, Unit closestEnemy) {
        int damage = (worker.getMinDamage() + worker.getMaxDamage()) / 2;
        long enemyID = closestEnemy.getID();
        damages.put(enemyID, damage + damages.getOrDefault(enemyID, 0));
        if (damages.get(enemyID) >= closestEnemy.getHitPoints()) {
            aliveEnemies.remove(closestEnemy);
        }
    }
    
    private void defendRangedUnit(Unit unit) {
        List<Unit> myRanged = myUnits.stream().filter(u -> u.getAttackRange() > 1).collect(Collectors.toList());
        Unit closestRanged = closestUnitAfterMoveAction(unit, myRanged);
        // Currently unused; implement behavior as needed.
    }
    
    private boolean moveDefensePosition(Unit warrior) {
        Map<Long, List<Integer>> baseDefensePositionsLocal;
        if (warrior.getAttackRange() > 1) {
            baseDefensePositionsLocal = this.baseDefensePositionsRanged;
        } else {
            baseDefensePositionsLocal = this.baseDefensePositions;
        }
        Unit closestBase = closestUnit(warrior, myBases);
        if (closestBase == null) {
            return false;
        }
        Unit closestEnemyFromBase = closestUnit(closestBase, aliveEnemies);
        if (closestEnemyFromBase == null) {
            return false;
        }
        List<Integer> defensePositions = baseDefensePositionsLocal.get(closestBase.getID());
        int bestPos = -1;
        int minDist = Integer.MAX_VALUE;
        for (Integer defensePos : defensePositions) {
            Unit existingUnit = pgs.getUnitAt(pgsIntToPosX(defensePos), pgsIntToPosY(defensePos));
            if (existingUnit != null && isAllyUnit(existingUnit)) {
                continue;
            }
            int dist = astar.findDistToPositionInRange(warrior, defensePos, 0, gs, gs.getResourceUsage());
            int enemyDist = distance(closestEnemyFromBase, pgsIntToPosX(defensePos), pgsIntToPosY(defensePos));
            if (dist != -1 && enemyDist < minDist) {
                minDist = enemyDist;
                bestPos = defensePos;
            }
        }
        if (bestPos == -1) {
            return false;
        }
        int workerPos = warrior.getPosition(pgs);
        if (defensePositions.contains(workerPos)) {
            if (minDist <= distance(closestEnemyFromBase, pgsIntToPosX(workerPos), pgsIntToPosY(workerPos))) {
                return false;
            }
        }
        move(warrior, pgsIntToPosX(bestPos), pgsIntToPosY(bestPos));
        return true;
    }
    
    private int pgsPos(int x, int y) {
        return x + pgs.getWidth() * y;
    }
    
    private int pgsIntToPosX(int pos) {
        return pos % pgs.getWidth();
    }
    
    private Position pgsIntToPos(int pos) {
        return new Position(pgsIntToPosX(pos), pgsIntToPosY(pos));
    }
    
    private int pgsIntToPosY(int pos) {
        return pos / pgs.getWidth();
    }
    
    private void harvestClosest(Unit worker) {
        Unit closestResource = closestUnit(worker, myClosestResources);
        Unit closestBase = closestUnit(worker, myBases.stream().filter(base -> !baseHasEnoughHarvester(base)).collect(Collectors.toList()));
        printDebug(worker + " harvesting " + closestResource);
        harvesting.put(worker.getID(), closestBase.getID());
        harvest(worker, closestResource, closestBase);
    }
    
    private int harvestScore(Unit worker) {
        Unit closestResource = closestUnit(worker, myClosestResources);
        Unit closestBase = closestUnit(worker, myBases.stream().filter(base -> !baseHasEnoughHarvester(base)).collect(Collectors.toList()));
        if (closestBase == null || closestResource == null) {
            return Integer.MAX_VALUE;
        }
        return distance(worker, closestBase) + distance(worker, closestResource);
    }
    
    private boolean baseHasEnoughHarvester(Unit base) {
        return harvesting.values().stream().filter(b -> b == base.getID()).count() >= 2;
    }
    
    private Unit closestUnit(Unit worker, List<Unit> list) {
        return list.stream().min(Comparator.comparing(u -> distance(worker, u))).orElse(null);
    }
    
    private Unit bestToAttack(Unit worker, List<Unit> enemies) {
        return enemies.stream().max((a, b) -> {
            int aRemainingHP = a.getHitPoints() - damages.getOrDefault(a.getID(), 0) - worker.getMinDamage();
            int bRemainingHP = b.getHitPoints() - damages.getOrDefault(b.getID(), 0) - worker.getMinDamage();
            if (aRemainingHP <= 0 && bRemainingHP <= 0) {
                return aRemainingHP - bRemainingHP;
            }
            if (aRemainingHP <= 0) {
                return 1;
            }
            if (bRemainingHP <= 0) {
                return -1;
            }
            return -distance(worker, a) + distance(worker, b);
        }).orElse(null);
    }
    
    private Unit closestUnitAfterMoveAction(Unit worker, List<Unit> enemies) {
        return enemies.stream().min(Comparator.comparing(u -> distance(worker, nextPos(u, gs)))).orElse(null);
    }
    
    private boolean isEnemyUnit(Unit u) {
        return u.getPlayer() >= 0 && u.getPlayer() != p.getID();
    }
    
    private boolean isAllyUnit(Unit u) {
        return u.getPlayer() == p.getID();
    }
    
    @Override
    public List<ParameterSpecification> getParameters() {
        List<ParameterSpecification> params = new ArrayList<>();
        // Expose aggressionLevel and the Nash equilibrium factor as parameters
        params.add(new ParameterSpecification("AggressionLevel", double.class, aggressionLevel));
        params.add(new ParameterSpecification("NashEquilibriumFactor", double.class, nashEquilibriumFactor));
        return params;
    }
    
    private boolean willBeMoveBeforeAttack(Unit unit, Unit closestEnemy) {
        UnitActionAssignment aa = gs.getActionAssignment(closestEnemy);
        if (aa == null) {
            return false;
        }
        if (aa.action.getType() != UnitAction.TYPE_MOVE) {
            return false;
        }
        int eta = aa.action.ETA(closestEnemy) - (gs.getTime() - aa.time);
        return eta <= unit.getAttackTime();
    }
    
    public void train(Unit u, UnitType unit_type) {
        if (gs.getActionAssignment(u) != null) {
            return;
        }
        if (p.getResources() - resourceUsed < unit_type.cost) {
            return;
        }
        resourceUsed += unit_type.cost;
        List<Integer> directions = new ArrayList<>();
        directions.add(UnitAction.DIRECTION_UP);
        directions.add(UnitAction.DIRECTION_DOWN);
        directions.add(UnitAction.DIRECTION_LEFT);
        directions.add(UnitAction.DIRECTION_RIGHT);
        int bestDirection = directions.stream().max(Comparator.comparingInt(d -> scoreTrainDirection(u, d))).orElse(-1);
        if (scoreTrainDirection(u, bestDirection) == Integer.MIN_VALUE) {
            printDebug(u + " failed to train");
            return;
        }
        actions.put(u, new TrainDirection(u, unit_type, bestDirection));
    }
    
    int scoreTrainDirection(Unit u, int direction) {
        int newPosX = u.getX() + UnitAction.DIRECTION_OFFSET_X[direction];
        int newPosY = u.getY() + UnitAction.DIRECTION_OFFSET_Y[direction];
        Position pos = new Position(newPosX, newPosY);
        if (!isValidPos(pos) || !gs.free(newPosX, newPosY)) {
            return Integer.MIN_VALUE;
        }
        Unit enemy = closestUnit(u, enemies);
        return -distance(enemy, pos);
    }
    
    // ----------------------------------------------------------------------
    // Inner Class for Position
    // ----------------------------------------------------------------------
    
    public static class Position {
        int x;
        int y;
        public Position(int x, int y) {
            this.x = x;
            this.y = y;
        }
        public List<Position> adjacentPos() {
            return Stream.of(
                    new Position(x, y + 1),
                    new Position(x, y - 1),
                    new Position(x + 1, y),
                    new Position(x - 1, y)
            ).collect(Collectors.toList());
        }
        @Override
        public String toString() {
            return "Position{" + "x=" + x + ", y=" + y + '}';
        }
        public int getX() {
            return x;
        }
        public int getY() {
            return y;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Position position = (Position) o;
            return x == position.x && y == position.y;
        }
        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }
    
    public static Position nextPos(Unit target, GameState gs) {
        UnitAction targetAction = gs.getUnitAction(target);
        if (targetAction != null && targetAction.getType() == UnitAction.TYPE_MOVE) {
            if (targetAction.getDirection() != UnitAction.DIRECTION_NONE) {
                int newPosX = target.getX() + UnitAction.DIRECTION_OFFSET_X[targetAction.getDirection()];
                int newPosY = target.getY() + UnitAction.DIRECTION_OFFSET_Y[targetAction.getDirection()];
                return new Position(newPosX, newPosY);
            }
        }
        return new Position(target.getX(), target.getY());
    }
    
    private boolean isValidPos(Position pos) {
        return pos.x < pgs.getWidth() && pos.x >= 0 && pos.y < pgs.getHeight() && pos.y >= 0;
    }
    
    private boolean isFreePos(Position pos) {
        if (pgs.getTerrain(pos.getX(), pos.getY()) == PhysicalGameState.TERRAIN_WALL) {
            return false;
        }
        Unit unitAtPos = pgs.getUnitAt(pos.getX(), pos.getY());
        return unitAtPos == null;
    }
    
    public static boolean enemyIsInRangeAttack(Unit ourUnit, Unit closestUnit) {
        return squareDist(ourUnit, closestUnit) <= ourUnit.getAttackRange();
    }
    
    static double squareDist(Unit ourUnit, Unit closestUnit) {
        int dx = closestUnit.getX() - ourUnit.getX();
        int dy = closestUnit.getY() - ourUnit.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    public static double squareDist(Unit ourUnit, Position pos2) {
        int dx = pos2.getX() - ourUnit.getX();
        int dy = pos2.getY() - ourUnit.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    int distance(Unit u1, Unit u2) {
        return Math.abs(u1.getX() - u2.getX()) + Math.abs(u1.getY() - u2.getY());
    }
    
    static int distance(Unit u1, Position pos2) {
        return Math.abs(u1.getX() - pos2.getX()) + Math.abs(u1.getY() - pos2.getY());
    }
    
    static int distance(Position u1, Position pos2) {
        return Math.abs(u1.getX() - pos2.getX()) + Math.abs(u1.getY() - pos2.getY());
    }
    
    static int distance(Unit u1, int x, int y) {
        return Math.abs(u1.getX() - x) + Math.abs(u1.getY() - y);
    }
    
    static int distanceChebyshev(Unit u1, int x2, int y2) {
        return Math.max(Math.abs(x2 - u1.getX()), Math.abs(y2 - u1.getY()));
    }
    
    static Position centroid(List<Unit> units) {
        int centroidX = 0, centroidY = 0;
        for (Unit unit : units) {
            centroidX += unit.getX();
            centroidY += unit.getY();
        }
        return new Position(centroidX / units.size(), centroidY / units.size());
    }
    
    static int oppositeDirection(int direction) {
        if (direction == UnitAction.DIRECTION_UP) {
            return UnitAction.DIRECTION_DOWN;
        }
        if (direction == UnitAction.DIRECTION_DOWN) {
            return UnitAction.DIRECTION_UP;
        }
        if (direction == UnitAction.DIRECTION_LEFT) {
            return UnitAction.DIRECTION_RIGHT;
        }
        if (direction == UnitAction.DIRECTION_RIGHT) {
            return UnitAction.DIRECTION_LEFT;
        }
        throw new RuntimeException("direction not recognized");
    }
}
