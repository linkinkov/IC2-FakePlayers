package ic2.core;

import ic2.api.event.ExplosionEvent;
import ic2.core.util.Util;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.gamerforea.ic2.EventConfig;
import com.gamerforea.ic2.FakePlayerUtils;

public class PointExplosion extends Explosion
{
	private final World world;
	private final Entity entity;
	private final float dropRate;
	private final int entityDamage;

	public PointExplosion(World world, Entity entity, EntityLivingBase exploder, double x, double y, double z, float power, float dropRate, int entityDamage)
	{
		super(world, exploder, x, y, z, power);
		this.world = world;
		this.entity = entity;
		this.dropRate = dropRate;
		this.entityDamage = entityDamage;
	}

	public void doExplosionA()
	{
		// TODO gamerforEA code start
		if (!EventConfig.explosionEnabled) return;
		// TODO gamerforEA code end
		ExplosionEvent event = new ExplosionEvent(this.world, this.entity, this.explosionX, this.explosionY, this.explosionZ, (double) this.explosionSize, (EntityLivingBase) this.exploder, 0, 1.0D);
		if (!MinecraftForge.EVENT_BUS.post(event))
		{
			for (int x = Util.roundToNegInf(this.explosionX) - 1; x <= Util.roundToNegInf(this.explosionX) + 1; ++x)
			{
				for (int y = Util.roundToNegInf(this.explosionY) - 1; y <= Util.roundToNegInf(this.explosionY) + 1; ++y)
				{
					for (int z = Util.roundToNegInf(this.explosionZ) - 1; z <= Util.roundToNegInf(this.explosionZ) + 1; ++z)
					{
						Block block = this.world.getBlock(x, y, z);
						if (block.getExplosionResistance(this.exploder, this.world, x, y, z, this.explosionX, this.explosionY, this.explosionZ) < this.explosionSize * 10.0F)
						{
							// TODO gamerforEA code start
							if (EventConfig.explosionEvent && FakePlayerUtils.isInPrivate(this.world, x, y, z)) continue;
							// TODO gamerforEA code end
							this.affectedBlockPositions.add(new ChunkPosition(x, y, z));
						}
					}
				}
			}

			List<Entity> entities = this.world.getEntitiesWithinAABBExcludingEntity(this.exploder, AxisAlignedBB.getBoundingBox(this.explosionX - 2.0D, this.explosionY - 2.0D, this.explosionZ - 2.0D, this.explosionX + 2.0D, this.explosionY + 2.0D, this.explosionZ + 2.0D));
			for (Entity entity : entities)
			{
				entity.attackEntityFrom(DamageSource.setExplosionSource(this), (float) this.entityDamage);
			}

			this.explosionSize = 1.0F / this.dropRate;
		}
	}
}