package com.yyon.grapplinghook.physics.rope;

import com.yyon.grapplinghook.GrappleMod;
import com.yyon.grapplinghook.content.entity.grapplinghook.GrapplinghookEntity;
import com.yyon.grapplinghook.integration.GrappleModIntegrations;
import com.yyon.grapplinghook.physics.io.RopeSnapshot;
import com.yyon.grapplinghook.physics.raycast.MultiSpaceRaycaster;
import com.yyon.grapplinghook.util.GrappleModUtils;
import com.yyon.grapplinghook.util.NullableDirection;
import com.yyon.grapplinghook.util.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class RopeSegmentHandler {

	private static final double BEND_OFFSET = 0.05d;
	private static final double INTO_BLOCK = 0.05d;

	final GrapplinghookEntity hookEntity;
	final Level world;

	LinkedList<RopeBend> bends;

	private Vec prevHookPos;
	private Vec prevHolderPos;

	private double ropeLen;

	public RopeSegmentHandler(GrapplinghookEntity hookEntity, Vec hookpos, Vec playerpos) {
		this.bends = new LinkedList<>();

		this.pushSegment(hookpos, null, null);
		this.pushSegment(playerpos, null, null);

		this.world = hookEntity.level();
		this.hookEntity = hookEntity;
		this.prevHookPos = new Vec(hookpos);
		this.prevHolderPos = new Vec(playerpos);
	}

	public void loadFromSnapshot(RopeSnapshot snapshot) {
		this.bends = new LinkedList<>();
		for (RopeBend src : snapshot.getBends()) {
			Vec3 native_ = src.space.worldToNative(src.worldPos.toVec3d(), CONTRAPTION_PARTIAL_TICKS, this.world);
			RopeBend fixed = (native_ != null)
					? new RopeBend(src.space, src.worldPos,
							new Vec(native_.x, native_.y, native_.z), src.topSide, src.bottomSide)
					: src;
			this.bends.add(fixed);
		}
	}

	private void pushSegment(Vec segment, Direction topSide, Direction bottomSide) {
		this.bends.add(RopeBend.world(segment, topSide, bottomSide));
	}

	private void removeSegmentAt(int index) {
		this.bends.remove(index);
	}


	public void forceSetPos(Vec hookpos, Vec playerpos) {
		this.prevHookPos = new Vec(hookpos);
		this.prevHolderPos = new Vec(playerpos);
		this.setEndpoint(0, new Vec(hookpos));
		this.setEndpoint(this.bends.size() - 1, new Vec(playerpos));
	}

	public void updatePos(Vec hookpos, Vec playerpos, double ropelen) {
		this.setEndpoint(0, hookpos);
		this.setEndpoint(this.bends.size() - 1, playerpos);
		this.ropeLen = ropelen;
	}

	private static final double SHRINK_MARGIN = 0.01;
	private static final float CONTRAPTION_PARTIAL_TICKS = 1.0f;

	public void update(Vec hookpos, Vec playerpos, double ropelen, boolean movinghook) {
		if (this.prevHookPos == null) {
			this.prevHookPos = hookpos;
			this.prevHolderPos = playerpos;
		}

		this.setEndpoint(0, hookpos);
		this.setEndpoint(this.bends.size() - 1, playerpos);
		this.ropeLen = ropelen;

		this.refreshWorldCoords();

		this.unwrapPass(hookpos, playerpos, movinghook);
		this.redundantBendSweep();
		this.movingHostSweep();
		this.wrapPass(hookpos, playerpos, movinghook);

		this.prevHookPos = hookpos;
		this.prevHolderPos = playerpos;
	}

	private void unwrapPass(Vec hookpos, Vec playerpos, boolean movinghook) {
		while (this.bends.size() > 2) {
			int index = this.bends.size() - 2;
			RopeBend bend = this.bends.get(index);
			if (bend.topSide == null || bend.bottomSide == null) break;
			Vec closest = bend.worldPos;
			Vec ropevec = playerpos.sub(closest);
			Vec beforepoint = this.bends.get(index - 1).worldPos;
			Vec edgevec = this.getNormal(bend.bottomSide).cross(this.getNormal(bend.topSide));
			Vec planenormal = beforepoint.sub(closest).cross(edgevec);
			if (ropevec.dot(planenormal) > 0) {
				this.removeSegment(index);
			} else break;
		}

		if (!movinghook) return;

		while (this.bends.size() > 2) {
			int index = 1;
			RopeBend bend = this.bends.get(index);
			if (bend.topSide == null || bend.bottomSide == null) break;
			Vec farthest = bend.worldPos;
			Vec ropevec = farthest.sub(hookpos);
			Vec beforepoint = this.bends.get(index + 1).worldPos;
			Vec edgevec = this.getNormal(bend.bottomSide).cross(this.getNormal(bend.topSide));
			Vec planenormal = beforepoint.sub(farthest).cross(edgevec);
			if (ropevec.dot(planenormal) > 0 || ropevec.length() < 0.1) {
				this.removeSegment(index);
			} else break;
		}

		while (this.bends.size() > 2 && this.getDistToFarthest() > this.ropeLen) {
			if (!this.bends.get(1).space.isStatic()) break;
			this.removeSegment(1);
		}
	}

	private void refreshWorldCoords() {
		if (this.bends.size() <= 2) return;
		for (int i = this.bends.size() - 2; i >= 1; i--) {
			if (i >= this.bends.size() - 1) continue;
			RopeBend bend = this.bends.get(i);
			if (bend.space.isStatic()) continue;
			if (!bend.space.refreshBendWorld(bend, this.world)) {
				this.removeSegment(i);
			}
		}
	}

	private void movingHostSweep() {
		if (!GrappleModIntegrations.hasContraptionIntegration()
				&& !GrappleModIntegrations.hasSubLevelIntegration()) return;
		int i = 1;
		while (i < this.bends.size()) {
			Vec top = this.bends.get(i - 1).worldPos;
			Vec bot = this.bends.get(i).worldPos;
			MultiSpaceRaycaster.MultiSpaceHit hit = MultiSpaceRaycaster.raycast(
					this.hookEntity, this.world, bot, top, CONTRAPTION_PARTIAL_TICKS);
			Vec inserted = null;
			if (hit != null && hit.space() instanceof AnchorSpace.Contraption c) {
				inserted = RopeBendInsertion.insertContraption(this, i, hit, c);
			} else if (hit != null && hit.space() instanceof AnchorSpace.SubLevel sl) {
				inserted = RopeBendInsertion.insertSubLevel(this, top, i, hit, sl);
			}
			if (inserted != null) {
				i++;
			}
			i++;
		}
	}

	private void redundantBendSweep() {
		boolean hookOnMovingHost = this.hookEntity.isAttachedToMovingBody();
		for (int i = this.bends.size() - 2; i >= 1; i--) {
			if (this.bends.size() <= 2) break;
			if (i >= this.bends.size() - 1) continue;
			RopeBend bend = this.bends.get(i);
			if (bend.space.isStatic() && !hookOnMovingHost) continue;

			Vec prev = this.bends.get(i - 1).worldPos;
			Vec next = this.bends.get(i + 1).worldPos;
			Vec direction = next.sub(prev);
			double length = direction.length();
			if (length > 0.02) {
				double shrink = Math.min(SHRINK_MARGIN, length * 0.1);
				Vec unit = direction.scale(1.0 / length);
				prev = prev.add(unit.scale(shrink));
				next = next.sub(unit.scale(shrink));
			}
			if (MultiSpaceRaycaster.raycast(this.hookEntity, this.world, prev, next, CONTRAPTION_PARTIAL_TICKS) == null) {
				this.removeSegment(i);
			}
		}
	}

	private void wrapPass(Vec hookpos, Vec playerpos, boolean movinghook) {
		if (movinghook) {
			Vec farthest = this.bends.get(1).worldPos;
			Vec prevfarthest = this.bends.size() == 2 ? this.prevHolderPos : farthest;
			this.updateSegment(hookpos, this.prevHookPos, farthest, prevfarthest, 1, 0);
		}

		Vec closest = this.bends.get(this.bends.size() - 2).worldPos;
		Vec prevclosest = this.bends.size() == 2 ? this.prevHookPos : closest;
		this.updateSegment(closest, prevclosest, playerpos, this.prevHolderPos, this.bends.size() - 1, 0);
	}




public void removeSegment(int index) {
		this.removeSegmentAt(index);
		RopeBroadcaster.broadcastRemove(this.hookEntity, this.world, index);
	}

	public void updateSegment(Vec top, Vec prevtop, Vec bottom, Vec prevbottom, int index, int numberrecursions) {
		MultiSpaceRaycaster.MultiSpaceHit msHit = MultiSpaceRaycaster.raycast(
				this.hookEntity, this.world, bottom, top, CONTRAPTION_PARTIAL_TICKS);
		if (msHit == null) return;

		if (msHit.space() instanceof AnchorSpace.Contraption c) {
			RopeBendInsertion.insertContraption(this, index, msHit, c);
			return;
		}

		if (msHit.space() instanceof AnchorSpace.SubLevel sl) {
			RopeBendInsertion.insertSubLevel(this, top, index, msHit, sl);
			return;
		}

		BlockHitResult bottomraytraceresult = GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, bottom, top);

        if (bottomraytraceresult != null) {
        	if (GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, prevbottom, prevtop) != null) {
        		return;
        	}

            Vec bottomhitvec = new Vec(bottomraytraceresult.getLocation());

            Direction bottomside = bottomraytraceresult.getDirection();
            Vec bottomnormal = this.getNormal(bottomside);

            double prevropelen = prevtop.sub(prevbottom).length();

            Vec cornerbound1 = bottomhitvec.add(bottomnormal.withMagnitude(-INTO_BLOCK));

            Vec bound_option1 = linePlaneIntersection(prevtop, prevbottom, cornerbound1, bottomnormal);
            Vec bound_option2 = linePlaneIntersection(top, prevtop, cornerbound1, bottomnormal);
            Vec bound_option3 = linePlaneIntersection(prevbottom, bottom, cornerbound1, bottomnormal);

            for (Vec cornerbound2 : new Vec[] {bound_option1, bound_option2, bound_option3}) {
            	if (cornerbound2 == null) {
            		continue;
            	}

            	BlockHitResult cornerraytraceresult = GrappleModUtils.rayTraceBlocks(this.hookEntity, this.world, cornerbound2, cornerbound1);
                if (cornerraytraceresult != null) {
                	Vec cornerhitpos = new Vec(cornerraytraceresult.getLocation());
                	Direction cornerside = cornerraytraceresult.getDirection();

                	if (!(cornerside == bottomside || cornerside.getOpposite() == bottomside)) {
                		Vec actualcorner = cornerhitpos.add(bottomnormal.withMagnitude(INTO_BLOCK));
                		Vec bend = actualcorner.add(bottomnormal.withMagnitude(BEND_OFFSET)).add(getNormal(cornerside).withMagnitude(BEND_OFFSET));
                		Vec topropevec = bend.sub(top);
                		Vec bottomropevec = bend.sub(bottom);

                		if (topropevec.length() < 0.05) {
                			if (this.bends.get(index - 1).bottomSide == bottomside && this.bends.get(index - 1).topSide == cornerside) {
                    			continue;
                			}
                		}
                		if (bottomropevec.length() < 0.05) {
                			if (this.bends.get(index).bottomSide == bottomside && this.bends.get(index).topSide == cornerside) {
                    			continue;
                			}
                		}

                		this.actuallyAddSegment(index, bend, bottomside, cornerside);

                		if(this.getDistToAnchor() + .2 > this.ropeLen) {
                			this.removeSegment(index);
                			continue;
                		}

                		double newropelen = topropevec.length() + bottomropevec.length();

                		double prevtoptobend = topropevec.length() * prevropelen / newropelen;
                		Vec prevbend = prevtop.add(prevbottom.sub(prevtop).withMagnitude(prevtoptobend));

                		if (numberrecursions < 10) {
                    		updateSegment(top, prevtop, bend, prevbend, index, numberrecursions+1);
                		} else {
                			GrappleMod.LOGGER.warn("Warning: number recursions exceeded");
                		}
                		break;
                	}
                }
            }
        }
	}

	public Vec linePlaneIntersection(Vec linepoint1, Vec linepoint2, Vec planepoint, Vec planenormal) {
		Vec linevec = linepoint2.sub(linepoint1);

		if (linevec.dot(planenormal) == 0) {
			return null;
		}

		double d = planepoint.sub(linepoint1).dot(planenormal) / linevec.dot(planenormal);
		return linepoint1.add(linevec.scale(d));
	}

	public boolean hookPastBend(double ropelen) {
		return (this.getDistToFarthest() > ropelen);
	}

	public void actuallyAddSegment(int index, Vec bendPoint, Direction bottomSide, Direction topSide) {
		this.actuallyAddSegment(index, bendPoint, NullableDirection.fromVanilla(bottomSide), NullableDirection.fromVanilla(topSide));
	}

	public void actuallyAddSegment(int index, Vec bendPoint, NullableDirection bottomSide, NullableDirection topSide) {
		this.addBend(index, RopeBend.world(bendPoint, topSide.toVanilla(), bottomSide.toVanilla()));
	}

	public void addBend(int index, RopeBend bend) {
		this.bends.add(index, bend);
		RopeBroadcaster.broadcastAdd(this.hookEntity, this.world, index, bend);
	}

	public Vec getNormal(Direction facing) {
		Vec3i facingvec = facing.getNormal();
		return new Vec(facingvec.getX(), facingvec.getY(), facingvec.getZ());
	}

	public BlockPos getBendBlock(int index) {
		RopeBend bend = this.bends.get(index);
		Vec bendpos = new Vec(bend.worldPos);
		if (bend.bottomSide != null)
			bendpos.mutableAdd(this.getNormal(bend.bottomSide).withMagnitude(-INTO_BLOCK * 2));
		if (bend.topSide != null)
			bendpos.mutableAdd(this.getNormal(bend.topSide).withMagnitude(-INTO_BLOCK * 2));
		return BlockPos.containing(bendpos.toVec3d());
	}

	public Vec getClosest(Vec hookpos) {
		if (this.bends.size() < 2) {
			GrappleMod.LOGGER.warn("[Grapple] getClosest called with bends.size()={}; segment handler invariant violated. side={} hookId={}",
					this.bends.size(),
					this.world != null && this.world.isClientSide ? "CLIENT" : "SERVER",
					this.hookEntity != null ? this.hookEntity.getId() : -1,
					new Throwable("getClosest size<2 trace"));
			return this.bends.isEmpty() ? hookpos : this.bends.get(0).worldPos;
		}
		this.setEndpoint(0, hookpos);
		return this.bends.get(this.bends.size() - 2).worldPos;
	}

	public double getDistToAnchor() {
		double dist = 0;
		for (int i = 0; i < this.bends.size() - 2; i++) {
			dist += this.bends.get(i).worldPos.sub(this.bends.get(i + 1).worldPos).length();
		}

		return dist;
	}

	public Vec getFarthest() {
		return this.bends.get(1).worldPos;
	}

	public double getDistToFarthest() {
		double dist = 0;
		for (int i = 1; i < this.bends.size() - 1; i++) {
			dist += this.bends.get(i).worldPos.sub(this.bends.get(i + 1).worldPos).length();
		}

		return dist;
	}

	public double getDist(Vec hookpos, Vec playerpos) {
		this.setEndpoint(0, hookpos);
		this.setEndpoint(this.bends.size() - 1, playerpos);
		double dist = 0;
		for (int i = 0; i < this.bends.size() - 1; i++) {
			dist += this.bends.get(i).worldPos.sub(this.bends.get(i + 1).worldPos).length();
		}

		return dist;
	}

	public AABB getBoundingBox(Vec hookpos, Vec playerpos) {
		this.updatePos(hookpos, playerpos, this.ropeLen);
		Vec minvec = new Vec(hookpos);
		Vec maxvec = new Vec(hookpos);
		for (int i = 1; i < this.bends.size(); i++) {
			Vec segpos = this.bends.get(i).worldPos;
			if (segpos.x < minvec.x) {
				minvec.x = segpos.x;
			} else if (segpos.x > maxvec.x) {
				maxvec.x = segpos.x;
			}
			if (segpos.y < minvec.y) {
				minvec.y = segpos.y;
			} else if (segpos.y > maxvec.y) {
				maxvec.y = segpos.y;
			}
			if (segpos.z < minvec.z) {
				minvec.z = segpos.z;
			} else if (segpos.z > maxvec.z) {
				maxvec.z = segpos.z;
			}
		}

		return new AABB(minvec.x, minvec.y, minvec.z, maxvec.x, maxvec.y, maxvec.z);
	}

	private void setEndpoint(int index, Vec pos) {
		this.bends.get(index).worldPos = pos;
	}

	public List<Vec> getSegments() {
		return this.bends.stream()
				.map(b -> b.worldPos)
				.toList();
	}

	public List<RopeBend> getBends() {
		return Collections.unmodifiableList(this.bends);
	}

	public double getCurrentRopeLength() {
		return this.ropeLen;
	}
}
