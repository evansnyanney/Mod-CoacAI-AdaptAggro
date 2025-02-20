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

public class MyValorAI extends AbstractionLayerAIWait1 {
    // Unit types
    protected UnitTypeTable utt;
    UnitType workerType;
    UnitType baseType;
    UnitType barracksType;
    UnitType rangedType;
    UnitType heavyType;
    UnitType lightType;

    // Game state tracking
    private GameState gs;
    private PhysicalGameState pgs;
    private Player p;
    private Player enemyPlayer;

    // Enhanced unit tracking
    private List<Unit> resources;
    private List<Unit> myClosestResources;
    private List<Unit> enemyBases;
    private List<Unit> myBases;
    private List<Unit> myWorkers;
    private List<Unit> myWorkersBusy;
    private List<Unit> enemies;
    private List<Unit> myUnits;
    private List<Unit> aliveEnemies;
    private List<Unit> myCombatUnits;
    private List<Unit> myCombatUnitsBusy;
    private List<Unit> myBarracks;
    private List<Unit> myWorkersCombat;

    // Advanced tracking systems
    private Map<Long, Long> harvesting;
    private Map<Long, Integer> damages;
    private Map<Long, List<Integer>> baseDefensePositions;

    // Strategy parameters
    private int resourceUsed;
    private boolean attackMode;
    private int defenseBaseDistance;
    private static final double LOW_HEALTH_THRESHOLD = 0.3;

    public MyValorAI(UnitTypeTable a_utt) {
        this(a_utt, new AStarPathFinding());
    }

    public MyValorAI(UnitTypeTable a_utt, AStarPathFinding a_pf) {
        super(a_pf);
        harvesting = new HashMap<>();
        damages = new HashMap<>();
        reset(a_utt);
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
        return new MyValorAI(utt, (AStarPathFinding) pf);
    }

    @Override
    public PlayerAction getAction(int player, GameState gs) {
        if (gs == null) return new PlayerAction();
        
        this.gs = gs;
        this.pgs = gs.getPhysicalGameState();
        p = gs.getPlayer(player);
        enemyPlayer = gs.getPlayer(1 - player);

        initializeState();
        updateStrategy();

        computeWorkersAction();
        computeCombatUnitsAction();
        computeBasesAction();

        return translateActions(player, gs);
    }

    private void initializeState() {
        resources = new ArrayList<>();
        myClosestResources = new ArrayList<>();
        enemyBases = new ArrayList<>();
        myBases = new ArrayList<>();
        myWorkers = new ArrayList<>();
        myWorkersBusy = new ArrayList<>();
        enemies = new ArrayList<>();
        myUnits = new ArrayList<>();
        aliveEnemies = new ArrayList<>();
        myCombatUnits = new ArrayList<>();
        myCombatUnitsBusy = new ArrayList<>();
        myBarracks = new ArrayList<>();
        myWorkersCombat = new ArrayList<>();

        for (Unit u : pgs.getUnits()) {
            if (u.getType().isResource) {
                resources.add(u);
            } else if (u.getType().isStockpile) {
                if (isEnemyUnit(u)) {
                    enemyBases.add(u);
                } else {
                    myBases.add(u);
                }
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
                }
            }

            if (isEnemyUnit(u)) {
                enemies.add(u);
                aliveEnemies.add(u);
            } else {
                myUnits.add(u);
            }
        }
    }

    private void updateStrategy() {
        if (pgs.getWidth() <= 8) {
            defenseBaseDistance = 2;
        } else if (pgs.getWidth() <= 16) {
            defenseBaseDistance = 3;
        } else if (pgs.getWidth() <= 24) {
            defenseBaseDistance = 4;
        }

        int myMilitaryScore = evaluateMilitaryScore(true);
        int enemyMilitaryScore = evaluateMilitaryScore(false);
        attackMode = myMilitaryScore > enemyMilitaryScore * 1.5 || gs.getTime() > 3000;
    }

    private void computeCombatUnitsAction() {
        for (Unit unit : myCombatUnits) {
            if (gs.getActionAssignment(unit) != null) continue;

            if (unit.getHitPoints() < unit.getMaxHitPoints() * LOW_HEALTH_THRESHOLD) {
                handleRetreat(unit);
                continue;
            }

            Unit target = selectBestTarget(unit);
            if (target != null) {
                attack(unit, target);
            } else {
                moveToStrategicPosition(unit);
            }
        }
    }

    private void handleRetreat(Unit unit) {
        Unit base = findNearestUnit(unit, myBases);
        if (base != null) {
            move(unit, base.getX(), base.getY());
        }
    }

    private void moveToStrategicPosition(Unit unit) {
        // Move towards enemy base or center of map
        Unit enemyBase = findNearestUnit(unit, enemyBases);
        if (enemyBase != null && attackMode) {
            move(unit, enemyBase.getX(), enemyBase.getY());
        } else {
            move(unit, pgs.getWidth()/2, pgs.getHeight()/2);
        }
    }

    private Unit selectBestTarget(Unit unit) {
        return aliveEnemies.stream()
            .filter(enemy -> canAttack(unit, enemy))
            .min(Comparator.comparingInt(enemy -> distance(unit, enemy)))
            .orElse(null);
    }

    private boolean canAttack(Unit attacker, Unit target) {
        int distance = distance(attacker, target);
        return distance <= attacker.getAttackRange();
    }

    private void computeWorkersAction() {
        for (Unit worker : myWorkers) {
            if (gs.getActionAssignment(worker) != null) continue;

            // Handle worker combat if needed
            if (shouldWorkerFight(worker)) {
                handleWorkerCombat(worker);
                continue;
            }

            // Handle resource gathering
            handleWorkerGathering(worker);
        }
    }

    private boolean shouldWorkerFight(Unit worker) {
        Unit nearestEnemy = findNearestUnit(worker, enemies);
        return nearestEnemy != null && distance(worker, nearestEnemy) <= 2;
    }

    private void handleWorkerCombat(Unit worker) {
        Unit target = findNearestUnit(worker, enemies);
        if (target != null) {
            attack(worker, target);
        }
    }

    private void handleWorkerGathering(Unit worker) {
        Unit base = findNearestUnit(worker, myBases);
        Unit resource = findNearestUnit(worker, resources);
        
        if (base != null && resource != null) {
            harvest(worker, resource, base);
        }
    }

    private void computeBasesAction() {
        for (Unit base : myBases) {
            if (gs.getActionAssignment(base) != null) continue;

            if (shouldTrainWorker()) {
                train(base, workerType);
            }
        }
    }

    private boolean shouldTrainWorker() {
        return myWorkers.size() + myWorkersBusy.size() < 4 * myBases.size() 
               && p.getResources() >= workerType.cost;
    }

    private Unit findNearestUnit(Unit unit, List<Unit> targets) {
        return targets.stream()
            .min(Comparator.comparingInt(target -> distance(unit, target)))
            .orElse(null);
    }

    private int distance(Unit a, Unit b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }

    private boolean isEnemyUnit(Unit u) {
        return u.getPlayer() >= 0 && u.getPlayer() != p.getID();
    }

    private boolean isAllyUnit(Unit u) {
        return u.getPlayer() == p.getID();
    }

    private int evaluateMilitaryScore(boolean ally) {
        List<Unit> units = ally ? myUnits : enemies;
        return units.stream()
                   .filter(u -> u.getType().canAttack)
                   .mapToInt(u -> u.getCost())
                   .sum();
    }

    @Override
    public List<ParameterSpecification> getParameters() {
        return new ArrayList<>();
    }
}