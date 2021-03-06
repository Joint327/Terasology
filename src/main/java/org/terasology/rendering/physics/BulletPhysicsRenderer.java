/*
 * Copyright 2012 Benjamin Glatzel <benjamin.glatzel@me.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.physics;

import com.bulletphysics.collision.broadphase.BroadphaseInterface;
import com.bulletphysics.collision.broadphase.DbvtBroadphase;
import com.bulletphysics.collision.dispatch.*;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.BvhTriangleMeshShape;
import com.bulletphysics.collision.shapes.IndexedMesh;
import com.bulletphysics.collision.shapes.TriangleIndexVertexArray;
import com.bulletphysics.collision.shapes.voxel.VoxelWorldShape;
import com.bulletphysics.dynamics.DiscreteDynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.dynamics.constraintsolver.SequentialImpulseConstraintSolver;
import com.bulletphysics.linearmath.DefaultMotionState;
import com.bulletphysics.linearmath.Transform;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.terasology.asset.AssetType;
import org.terasology.asset.AssetUri;
import org.terasology.components.CharacterMovementComponent;
import org.terasology.components.InventoryComponent;
import org.terasology.components.ItemComponent;
import org.terasology.components.world.BlockComponent;
import org.terasology.components.world.LocationComponent;
import org.terasology.entityFactory.BlockItemFactory;
import org.terasology.entitySystem.*;
import org.terasology.events.inventory.ReceiveItemEvent;
import org.terasology.game.CoreRegistry;
import org.terasology.game.Timer;
import org.terasology.logic.manager.AudioManager;
import org.terasology.logic.world.BlockChangedEvent;
import org.terasology.logic.world.BlockEntityRegistry;
import org.terasology.logic.world.chunks.Chunk;
import org.terasology.math.Vector3i;
import org.terasology.model.blocks.Block;
import org.terasology.model.blocks.management.BlockManager;
import org.terasology.rendering.interfaces.IGameObject;
import org.terasology.rendering.primitives.ChunkMesh;
import org.terasology.rendering.world.WorldRenderer;
import org.terasology.utilities.FastRandom;

import javax.vecmath.*;
import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders blocks using the Bullet physics library.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public class BulletPhysicsRenderer implements IGameObject, EventReceiver<BlockChangedEvent> {

    private class BlockRigidBody extends RigidBody implements Comparable<BlockRigidBody> {
        private final byte _type;
        private final long _createdAt;

        public boolean _temporary = false;
        public boolean _picked = false;

        public BlockRigidBody(RigidBodyConstructionInfo constructionInfo, byte type) {
            super(constructionInfo);
            _type = type;
            _createdAt = _timer.getTimeInMs();
        }

        public float distanceToEntity(Vector3f pos) {
            Transform t = new Transform();
            getMotionState().getWorldTransform(t);
            Matrix4f tMatrix = new Matrix4f();
            t.getMatrix(tMatrix);
            Vector3f blockPlayer = new Vector3f();
            tMatrix.get(blockPlayer);
            blockPlayer.sub(pos);
            return blockPlayer.length();
        }

        public long calcAgeInMs() {
            return _timer.getTimeInMs() - _createdAt;
        }

        public byte getType() {
            return _type;
        }

        @Override
        public int compareTo(BlockRigidBody blockRigidBody) {
            if (blockRigidBody.calcAgeInMs() == calcAgeInMs()) {
                return 0;
            } else if (blockRigidBody.calcAgeInMs() > calcAgeInMs()) {
                return 1;
            } else {
                return -1;
            }
        }
    }

    public enum BLOCK_SIZE {
        FULL_SIZE,
        HALF_SIZE,
        QUARTER_SIZE
    }

    private static final int MAX_TEMP_BLOCKS = 128;
    private Logger _logger = Logger.getLogger(getClass().getName());

    private final LinkedList<RigidBody> _insertionQueue = new LinkedList<RigidBody>();
    private final LinkedList<BlockRigidBody> _blocks = new LinkedList<BlockRigidBody>();

    private HashSet<RigidBody> _chunks = new HashSet<RigidBody>();

    private final BoxShape _blockShape = new BoxShape(new Vector3f(0.5f, 0.5f, 0.5f));
    private final BoxShape _blockShapeHalf = new BoxShape(new Vector3f(0.25f, 0.25f, 0.25f));
    private final BoxShape _blockShapeQuarter = new BoxShape(new Vector3f(0.125f, 0.125f, 0.125f));

    private final CollisionDispatcher _dispatcher;
    private final BroadphaseInterface _broadphase;
    private final CollisionConfiguration _defaultCollisionConfiguration;
    private final SequentialImpulseConstraintSolver _sequentialImpulseConstraintSolver;
    private final DiscreteDynamicsWorld _discreteDynamicsWorld;
    private final BlockEntityRegistry blockEntityRegistry;

    private final BlockItemFactory _blockItemFactory;

    private Timer _timer;
    private FastRandom _random = new FastRandom();
    private final WorldRenderer _parent;

    public BulletPhysicsRenderer(WorldRenderer parent) {
        _broadphase = new DbvtBroadphase();
        _defaultCollisionConfiguration = new DefaultCollisionConfiguration();
        _dispatcher = new CollisionDispatcher(_defaultCollisionConfiguration);
        _sequentialImpulseConstraintSolver = new SequentialImpulseConstraintSolver();
        _discreteDynamicsWorld = new DiscreteDynamicsWorld(_dispatcher, _broadphase, _sequentialImpulseConstraintSolver, _defaultCollisionConfiguration);
        _discreteDynamicsWorld.setGravity(new Vector3f(0f, -10f, 0f));
        _parent = parent;
        _blockItemFactory = new BlockItemFactory(CoreRegistry.get(EntityManager.class), CoreRegistry.get(PrefabManager.class));
        blockEntityRegistry = CoreRegistry.get(BlockEntityRegistry.class);
        _timer = CoreRegistry.get(Timer.class);
        CoreRegistry.get(EventSystem.class).registerEventReceiver(this, BlockChangedEvent.class, BlockComponent.class);

        PhysicsWorldWrapper wrapper = new PhysicsWorldWrapper(parent.getWorldProvider());
        VoxelWorldShape worldShape = new VoxelWorldShape(wrapper);

        Matrix3f rot = new Matrix3f();
        rot.setIdentity();
        DefaultMotionState blockMotionState = new DefaultMotionState(new Transform(new Matrix4f(rot, new Vector3f(0, 0, 0), 1.0f)));
        RigidBodyConstructionInfo blockConsInf = new RigidBodyConstructionInfo(0, blockMotionState, worldShape, new Vector3f());
        RigidBody rigidBody = new RigidBody(blockConsInf);
        _discreteDynamicsWorld.addRigidBody(rigidBody);

    }

    public HitResult rayTrace(Vector3f from, Vector3f direction, float distance) {
        Vector3f to = new Vector3f(direction);
        to.scale(distance);
        to.add(from);

        CollisionWorld.ClosestRayResultWithUserDataCallback closest = new CollisionWorld.ClosestRayResultWithUserDataCallback(from, to);
        _discreteDynamicsWorld.rayTest(from, to, closest);
        if (closest.userData instanceof Vector3i) {
            return new HitResult(blockEntityRegistry.getOrCreateEntityAt((Vector3i)closest.userData), closest.hitPointWorld, closest.hitNormalWorld);
        }
        return new HitResult();
    }

    @Override
    public void onEvent(BlockChangedEvent event, EntityRef entity) {
        Vector3f min = event.getBlockPosition().toVector3f();
        min.sub(new Vector3f(0.6f, 0.6f, 0.6f));
        Vector3f max = event.getBlockPosition().toVector3f();
        max.add(new Vector3f(0.6f, 0.6f, 0.6f));
        _discreteDynamicsWorld.awakenRigidBodiesInArea(min, max);
    }

    public BlockRigidBody[] addLootableBlocks(Vector3f position, Block block) {
        BlockRigidBody result[] = new BlockRigidBody[8];
        for (int i = 0; i < 1; i++) {
            // Position the smaller blocks
            Vector3f offsetPossition = new Vector3f((float) _random.randomDouble() * 0.5f, (float) _random.randomDouble() * 0.5f, (float) _random.randomDouble() * 0.5f);
            offsetPossition.add(position);
            result[i] = addBlock(offsetPossition, block.getId(), new Vector3f(0.0f, 0f, 0.0f), BLOCK_SIZE.QUARTER_SIZE, false);
        }
        return result;
    }

    public BlockRigidBody addTemporaryBlock(Vector3f position, byte type, BLOCK_SIZE size) {
        BlockRigidBody result = addBlock(position, type, size, true);
        return result;
    }

    public BlockRigidBody addTemporaryBlock(Vector3f position, byte type, Vector3f impulse, BLOCK_SIZE size) {
        BlockRigidBody result = addBlock(position, type, impulse, size, true);
        return result;
    }

    public BlockRigidBody addBlock(Vector3f position, byte type, BLOCK_SIZE size, boolean temporary) {
        return addBlock(position, type, new Vector3f(0f, 0f, 0f), size, temporary);
    }

    /**
     * Adds a new physics block to be rendered as a rigid body. Translucent blocks are ignored.
     *
     * @param position The position
     * @param type     The block type
     * @param impulse  An impulse
     * @param size     The size of the block
     * @return The created rigid body (if any)
     */
    public synchronized BlockRigidBody addBlock(Vector3f position, byte type, Vector3f impulse, BLOCK_SIZE size, boolean temporary) {
        if (temporary && _blocks.size() > MAX_TEMP_BLOCKS)
          removeTemporaryBlocks();

        BoxShape shape = _blockShape;
        Block block = BlockManager.getInstance().getBlock(type);
        if (block.isTranslucent())
            return null;
        if (size == BLOCK_SIZE.HALF_SIZE)
            shape = _blockShapeHalf;
        else if (size == BLOCK_SIZE.QUARTER_SIZE)
            shape = _blockShapeQuarter;
        Matrix3f rot = new Matrix3f();
        rot.setIdentity();
        DefaultMotionState blockMotionState = new DefaultMotionState(new Transform(new Matrix4f(rot, position, 1.0f)));
        Vector3f fallInertia = new Vector3f();
        shape.calculateLocalInertia(10 * block.getMass(), fallInertia);
        RigidBodyConstructionInfo blockCI = new RigidBodyConstructionInfo(block.getMass(), blockMotionState, shape, fallInertia);
        BlockRigidBody rigidBlock = new BlockRigidBody(blockCI, type);
        rigidBlock.setRestitution(0.0f);
        rigidBlock.setAngularFactor(0.5f);
        rigidBlock.setFriction(0.5f);
        rigidBlock._temporary = temporary;
        // Apply impulse
        rigidBlock.applyImpulse(impulse, new Vector3f(0.0f, 0.0f, 0.0f));
        _insertionQueue.add(rigidBlock);

        return rigidBlock;
    }

    @Override
    public void render() {
        FloatBuffer mBuffer = BufferUtils.createFloatBuffer(16);
        float[] mFloat = new float[16];
        GL11.glPushMatrix();
        Vector3f cameraPosition = _parent.getActiveCamera().getPosition();
        GL11.glTranslated(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
        List<CollisionObject> collisionObjects = _discreteDynamicsWorld.getCollisionObjectArray();
        for (CollisionObject co : collisionObjects) {
            if (co.getClass().equals(BlockRigidBody.class)) {
                BlockRigidBody br = (BlockRigidBody) co;
                Block block = BlockManager.getInstance().getBlock(br.getType());
                Transform t = new Transform();
                br.getMotionState().getWorldTransform(t);
                t.getOpenGLMatrix(mFloat);
                mBuffer.put(mFloat);
                mBuffer.flip();
                GL11.glPushMatrix();
                GL11.glMultMatrix(mBuffer);
                if (br.getCollisionShape() == _blockShapeHalf)
                    GL11.glScalef(0.5f, 0.5f, 0.5f);
                else if (br.getCollisionShape() == _blockShapeQuarter)
                    GL11.glScalef(0.25f, 0.25f, 0.25f);
                block.renderWithLightValue(_parent.getRenderingLightValueAt(t.origin));
                GL11.glPopMatrix();
            }
        }
        GL11.glPopMatrix();
    }

    @Override
    public void update(float delta) {
        addQueuedBodies();
        try {
            _discreteDynamicsWorld.stepSimulation(delta, 3);
        } catch (Exception e) {
            _logger.log(Level.WARNING, "Somehow Bullet Physics managed to throw an exception again.", e);
        }
        removeTemporaryBlocks();
        checkForLootedBlocks();
    }

    private synchronized void addQueuedBodies() {
        while (!_insertionQueue.isEmpty()) {
            RigidBody body = _insertionQueue.poll();
            if (body instanceof BlockRigidBody)
                _blocks.addFirst((BlockRigidBody) body);
            _discreteDynamicsWorld.addRigidBody(body);
        }
    }

    private void checkForLootedBlocks() {
        for (int i = _blocks.size() - 1; i >= 0; i--) {
            BlockRigidBody b = _blocks.get(i);
            if (b._temporary) {
                continue;
            }

            EntityRef closestCreature = EntityRef.NULL;
            Vector3f closestPosition = new Vector3f();
            float closestDist = Float.MAX_VALUE;

            // TODO: We should have some other component for things that can pick up items? CreatureComponent? ItemMagnetComponent?
            for (EntityRef creature : CoreRegistry.get(EntityManager.class).iteratorEntities(InventoryComponent.class, CharacterMovementComponent.class, LocationComponent.class)) {
                Vector3f pos = creature.getComponent(LocationComponent.class).getWorldPosition();
                float dist = b.distanceToEntity(pos);
                if (dist < closestDist) {
                    closestDist = dist;
                    closestCreature = creature;
                    closestPosition.set(pos);
                }
            }

            if (closestDist < 8 && !b._picked) {
                b._picked = true;
            }
            // Block was marked as being picked
            if (b._picked && closestDist < 32.0f) {
                // Animate the movement in direction of the player
                if (closestDist > 1.0) {
                    Transform t = new Transform();
                    b.getMotionState().getWorldTransform(t);
                    Matrix4f tMatrix = new Matrix4f();
                    t.getMatrix(tMatrix);
                    Vector3f blockPlayer = new Vector3f();
                    tMatrix.get(blockPlayer);
                    blockPlayer.sub(new Vector3f(closestPosition));
                    blockPlayer.normalize();
                    blockPlayer.scale(-16000f);
                    b.applyCentralImpulse(blockPlayer);
                } else {
                    // TODO: Handle full inventories
                    // TODO: Loot blocks should be entities
                    // Block was looted (and reached the player)
                    Block block = BlockManager.getInstance().getBlock(b.getType());
                    EntityRef blockItem = _blockItemFactory.newInstance(block.getBlockFamily());
                    closestCreature.send(new ReceiveItemEvent(blockItem));

                    ItemComponent itemComp = blockItem.getComponent(ItemComponent.class);
                    if (itemComp != null && !itemComp.container.exists()) {
                        blockItem.destroy();
                    }
                    AudioManager.play(new AssetUri(AssetType.SOUND, "engine:Loot"));
                    _blocks.remove(i);
                    _discreteDynamicsWorld.removeRigidBody(b);
                }
            }
        }
    }

    private void removeTemporaryBlocks() {
        if (_blocks.size() > 0) {
            for (int i = _blocks.size() - 1; i >= 0; i--) {
                if (!_blocks.get(i)._temporary) {
                    continue;
                }
                if (!_blocks.get(i).isActive() || _blocks.get(i).calcAgeInMs() > 10000 || _blocks.size() > MAX_TEMP_BLOCKS) {
                    _discreteDynamicsWorld.removeRigidBody(_blocks.get(i));
                    _blocks.remove(i);
                }
            }
        }
    }
}
