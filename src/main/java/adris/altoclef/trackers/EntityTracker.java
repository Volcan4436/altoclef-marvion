package adris.altoclef.trackers;

import adris.altoclef.Debug;
import adris.altoclef.util.CachedProjectile;
import adris.altoclef.util.ProjectileUtil;
import adris.altoclef.util.baritone.BaritoneHelper;
import adris.altoclef.util.ItemTarget;
import baritone.process.MineProcess;
import it.unimi.dsi.fastutil.Hash;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.*;

@SuppressWarnings("rawtypes")
public class EntityTracker extends Tracker {

    private final HashMap<Item, List<ItemEntity>> _itemDropLocations = new HashMap<>();

    private final List<Vec3d> _blacklist = new ArrayList<>();

    private final HashMap<Class, List<MobEntity>> _mobMap = new HashMap<>();

    private final List<Entity> _closeEntities = new ArrayList<>();
    private final List<HostileEntity> _hostiles = new ArrayList<>();

    private final List<CachedProjectile> _projectiles = new ArrayList<>();

    public EntityTracker(TrackerManager manager) {
        super(manager);
    }

    public ItemEntity getClosestItemDrop(Vec3d position, Item ...items) {
        ensureUpdated();
        ItemTarget[] tempTargetList = new ItemTarget[items.length];
        for (int i = 0; i < items.length; ++i) {
            tempTargetList[i] = new ItemTarget(items[i], 9999999);
        }
        return getClosestItemDrop(position, tempTargetList);
        //return getClosestItemDrop(position, ItemTarget.getItemArray(_mod, targets));
    }

    public ItemEntity getClosestItemDrop(Vec3d position, ItemTarget ...targets) {
        ensureUpdated();
        if (targets.length == 0) {
            Debug.logError("You asked for the drop position of zero items... Most likely a typo.");
            return null;
        }
        if (!itemDropped(targets)) {
            Debug.logError("You forgot to check for whether item (example): " + targets[0].getMatches()[0].getTranslationKey() + " was dropped before finding its drop location.");
            return null;
        }

        ItemEntity closestEntity = null;
        float minCost = Float.POSITIVE_INFINITY;
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                if (!itemDropped(item)) continue;
                for (ItemEntity entity : _itemDropLocations.get(item)) {
                    if (isBlackListed(entity)) continue;
                    if (!entity.getStack().getItem().equals(item)) continue;

                    float cost = (float) BaritoneHelper.calculateGenericHeuristic(position, entity.getPos());
                    if (cost < minCost) {
                        minCost = cost;
                        closestEntity = entity;
                    }
                }
            }
        }
        return closestEntity;
    }
    private boolean isBlackListed(Entity entity) {
        if (entity == null) return false;
        return isBlackListed(entity.getPos());
    }
    private boolean isBlackListed(Vec3d pos) {
        for (Vec3d item : _blacklist) {
            double distSq = pos.squaredDistanceTo(item);
            if (distSq < 1) {
                return true;
            }
        }
        return false;
    }

    public void blacklist(Vec3d position) {
        _blacklist.add(position);
    }

    public void clearBlacklist() {
        _blacklist.clear();
    }

    public boolean itemDropped(Item ...items) {
        ensureUpdated();
        for(Item item : items) {
            if (_itemDropLocations.containsKey(item)) {
                // Find a non-blacklisted item
                for (ItemEntity entity : _itemDropLocations.get(item)) {
                    if (!isBlackListed(entity)) return true;
                }
            }
        }
        return false;
    }

    public boolean itemDropped(ItemTarget ...targets) {
        ensureUpdated();
        for (ItemTarget target : targets) {
            if (itemDropped(target.getMatches())) return true;
        }
        return false;
    }

    public boolean mobFound(Class type) {
        return _mobMap.containsKey(type);
    }

    public <T extends MobEntity> List<T> getTrackedMobs(Class<T> type) {
        ensureUpdated();
        if (!mobFound(type)) {
            return Collections.emptyList();
        }
        //noinspection unchecked
        return (List<T>) _mobMap.get(type);
    }

    public List<Entity> getCloseEntities() {
        ensureUpdated();
        return _closeEntities;
    }

    public List<CachedProjectile> getProjectiles() {
        ensureUpdated();
        return _projectiles;
    }

    public List<HostileEntity> getHostiles() {
        ensureUpdated();
        return _hostiles;
    }

    @Override
    protected synchronized void updateState() {
        _itemDropLocations.clear();
        _mobMap.clear();
        _closeEntities.clear();
        _projectiles.clear();
        _hostiles.clear();
        if (MinecraftClient.getInstance().world == null) return;

        // Loop through all entities and track 'em
        for(Entity entity : MinecraftClient.getInstance().world.getEntities()) {

            if (_mod.getControllerExtras().inRange(entity)) {
                _closeEntities.add(entity);
            }

            if (entity instanceof ItemEntity) {
                ItemEntity ientity = (ItemEntity) entity;
                Item droppedItem = ientity.getStack().getItem();

                if (!_itemDropLocations.containsKey(droppedItem)) {
                    _itemDropLocations.put(droppedItem, new ArrayList<>());
                }
                _itemDropLocations.get(droppedItem).add(ientity);
            } else if (entity instanceof MobEntity) {
                MobEntity mob = (MobEntity) entity;
                Class type = entity.getClass();

                if (!_mobMap.containsKey(type)) {
                    _mobMap.put(type, new ArrayList<>());
                }
                _mobMap.get(type).add(mob);

                if (entity instanceof HostileEntity) {

                    // Only run away if the hostile can see us.
                    HostileEntity hostile = (HostileEntity) entity;

                    if (hostile.canSee(_mod.getPlayer())) {
                        // Check if the mob is facing us or is close enough
                        boolean closeEnough = hostile.isInRange(_mod.getPlayer(), 4);

                        if (!closeEnough) {
                            if (hostile.getLookControl().isActive()) {
                                Vec3d lookTarget = new Vec3d(
                                        hostile.getLookControl().getLookX(),
                                        hostile.getLookControl().getLookY(),
                                        hostile.getLookControl().getLookZ()
                                );
                                Debug.logInternal("LOOK: " + lookTarget + " : " + lookTarget.subtract(_mod.getPlayer().getPos()));
                                if (lookTarget.isInRange(_mod.getPlayer().getPos(), 4f)) {
                                    closeEnough = true;
                                }
                            }
                        }

                        //Debug.logInternal("TARGET: " + hostile.is);
                        if (closeEnough && isAngryAtPlayer(hostile)) {
                            _hostiles.add(hostile);
                        }
                    }
                }

                /*
                if (mob instanceof HostileEntity) {
                    HostileEntity hostile = (HostileEntity) mob;
                }
                 */
            } else if (entity instanceof ProjectileEntity) {
                CachedProjectile proj = new CachedProjectile();
                ProjectileEntity projEntity = (ProjectileEntity)entity;

                boolean inGround = false;
                // Get projectile "inGround" variable
                try {
                    Field inGroundField = PersistentProjectileEntity.class.getDeclaredField("inGround");
                    inGroundField.setAccessible(true);
                    inGround = inGroundField.getBoolean(projEntity);
                } catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
                    e.printStackTrace();
                }

                if (!inGround) {
                    proj.position = projEntity.getPos();
                    proj.velocity = projEntity.getVelocity();
                    proj.gravity = ProjectileUtil.hasGravity(projEntity) ? ProjectileUtil.GRAVITY_ACCEL : 0;
                    _projectiles.add(proj);
                }
            }
        }
    }

    public static boolean isAngryAtPlayer(HostileEntity hostile) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (hostile instanceof EndermanEntity) {
            EndermanEntity enderman = (EndermanEntity) hostile;
            return enderman.isAngryAt(player) && enderman.isAngry();
        }
        if (hostile instanceof ZombifiedPiglinEntity) {
            ZombifiedPiglinEntity zombie = (ZombifiedPiglinEntity) hostile;
            // Will ALWAYS be false.
            return zombie.hasAngerTime() && zombie.isAngryAt(player);
        }
        return true;
    }
}