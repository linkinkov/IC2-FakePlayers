package ic2.core.block.machine.tileentity;

import ic2.api.Direction;
import ic2.api.item.ElectricItem;
import ic2.core.ContainerBase;
import ic2.core.IC2;
import ic2.core.IHasGui;
import ic2.core.Ic2Items;
import ic2.core.Ic2Player;
import ic2.core.InvSlotConsumableBlock;
import ic2.core.audio.AudioSource;
import ic2.core.audio.PositionSpec;
import ic2.core.block.IUpgradableBlock;
import ic2.core.block.invslot.InvSlot;
import ic2.core.block.invslot.InvSlotConsumable;
import ic2.core.block.invslot.InvSlotConsumableId;
import ic2.core.block.invslot.InvSlotUpgrade;
import ic2.core.block.machine.container.ContainerMiner;
import ic2.core.block.machine.gui.GuiMiner;
import ic2.core.init.MainConfig;
import ic2.core.item.IUpgradeItem;
import ic2.core.item.tool.ItemScanner;
import ic2.core.util.ConfigUtil;
import ic2.core.util.LiquidUtil;
import ic2.core.util.StackUtil;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.fluids.IFluidBlock;

import com.gamerforea.ic2.EventConfig;
import com.gamerforea.ic2.FakePlayerUtils;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class TileEntityMiner extends TileEntityElectricMachine implements IHasGui, IUpgradableBlock
{
	private TileEntityMiner.Mode lastMode;
	public int progress;
	private int scannedLevel;
	private int scanRange;
	private int lastX;
	private int lastZ;
	public boolean pumpMode;
	public boolean canProvideLiquid;
	public int liquidX;
	public int liquidY;
	public int liquidZ;
	private AudioSource audioSource;
	public final InvSlot buffer;
	public final InvSlotUpgrade upgradeSlot;
	public final InvSlotConsumable drillSlot;
	public final InvSlotConsumable pipeSlot;
	public final InvSlotConsumable scannerSlot;

	public TileEntityMiner()
	{
		super(1000, ConfigUtil.getInt(MainConfig.get(), "balance/minerDischargeTier"), 0, false);
		this.lastMode = TileEntityMiner.Mode.None;
		this.progress = 0;
		this.scannedLevel = -1;
		this.scanRange = 0;
		this.pumpMode = false;
		this.canProvideLiquid = false;
		this.drillSlot = new InvSlotConsumableId(this, "drill", 19, InvSlot.Access.IO, 1, InvSlot.InvSide.TOP, new Item[] { Ic2Items.miningDrill.getItem(), Ic2Items.diamondDrill.getItem() });
		this.pipeSlot = new InvSlotConsumableBlock(this, "pipe", 18, InvSlot.Access.IO, 1, InvSlot.InvSide.TOP);
		this.scannerSlot = new InvSlotConsumableId(this, "scanner", 17, InvSlot.Access.IO, 1, InvSlot.InvSide.BOTTOM, new Item[] { Ic2Items.odScanner.getItem(), Ic2Items.ovScanner.getItem() });
		this.upgradeSlot = new InvSlotUpgrade(this, "upgrade", 16, 1);
		this.buffer = new InvSlot(this, "buffer", 1, InvSlot.Access.IO, 15, InvSlot.InvSide.SIDE);
	}

	public void onLoaded()
	{
		super.onLoaded();
		this.scannedLevel = -1;
		this.lastX = this.xCoord;
		this.lastZ = this.zCoord;
		this.canProvideLiquid = false;
	}

	public void onUnloaded()
	{
		if (IC2.platform.isRendering() && this.audioSource != null)
		{
			IC2.audioManager.removeSources(this);
			this.audioSource = null;
		}

		super.onUnloaded();
	}

	public void readFromNBT(NBTTagCompound nbtTagCompound)
	{
		super.readFromNBT(nbtTagCompound);
		this.lastMode = TileEntityMiner.Mode.values()[nbtTagCompound.getInteger("lastMode")];
		this.progress = nbtTagCompound.getInteger("progress");
	}

	public void writeToNBT(NBTTagCompound nbtTagCompound)
	{
		super.writeToNBT(nbtTagCompound);
		nbtTagCompound.setInteger("lastMode", this.lastMode.ordinal());
		nbtTagCompound.setInteger("progress", this.progress);
	}

	public void updateEntity()
	{
		super.updateEntity();
		this.chargeTools();

		for (int i = 0; i < this.upgradeSlot.size(); ++i)
		{
			ItemStack stack = this.upgradeSlot.get(i);
			if (stack != null && stack.getItem() instanceof IUpgradeItem && ((IUpgradeItem) stack.getItem()).onTick(stack, this))
			{
				super.markDirty();
			}
		}

		if (this.work())
		{
			this.markDirty();
			this.setActive(true);
		}
		else
		{
			this.setActive(false);
		}
	}

	private void chargeTools()
	{
		if (!this.scannerSlot.isEmpty())
		{
			this.energy -= ElectricItem.manager.charge(this.scannerSlot.get(), this.energy, 2, false, false);
		}

		if (!this.drillSlot.isEmpty())
		{
			this.energy -= ElectricItem.manager.charge(this.drillSlot.get(), this.energy, 1, false, false);
		}
	}

	private boolean work()
	{
		int operationHeight = this.getOperationHeight();
		if (this.drillSlot.isEmpty())
		{
			return this.withDrawPipe(operationHeight);
		}
		else if (operationHeight >= 0)
		{
			Block block = this.worldObj.getBlock(this.xCoord, operationHeight, this.zCoord);
			if (!StackUtil.equals(block, Ic2Items.miningPipeTip))
			{
				return operationHeight > 0 ? this.digDown(operationHeight, false) : false;
			}
			else
			{
				TileEntityMiner.MineResult result = this.mineLevel(operationHeight);
				return result == TileEntityMiner.MineResult.Done ? this.digDown(operationHeight - 1, true) : result == TileEntityMiner.MineResult.Working;
			}
		}
		else
		{
			return false;
		}
	}

	private int getOperationHeight()
	{
		for (int y = this.yCoord - 1; y >= 0; --y)
		{
			Block block = this.worldObj.getBlock(this.xCoord, y, this.zCoord);
			if (!StackUtil.equals(block, Ic2Items.miningPipe))
			{
				return y;
			}
		}

		return -1;
	}

	private boolean withDrawPipe(int y)
	{
		if (this.lastMode != TileEntityMiner.Mode.Withdraw)
		{
			this.lastMode = TileEntityMiner.Mode.Withdraw;
			this.progress = 0;
		}

		if (y < 0 || !StackUtil.equals(this.worldObj.getBlock(this.xCoord, y, this.zCoord), Ic2Items.miningPipeTip))
		{
			++y;
		}

		if (y != this.yCoord && this.energy >= 3.0D)
		{
			if (this.progress < 20)
			{
				this.energy -= 3.0D;
				++this.progress;
			}
			else
			{
				this.progress = 0;
				this.removePipe(y);
			}

			return true;
		}
		else
		{
			return false;
		}
	}

	private void removePipe(int y)
	{
		this.worldObj.setBlockToAir(this.xCoord, y, this.zCoord);
		this.storeDrop(Ic2Items.miningPipe.copy());
		ItemStack pipe = this.pipeSlot.consume(1, true, false);
		if (pipe != null && pipe.getItem() != Ic2Items.miningPipe.getItem())
		{
			ItemStack filler = this.pipeSlot.consume(1);
			Item fillerItem = filler.getItem();
			if (fillerItem instanceof ItemBlock)
			{
				((ItemBlock) fillerItem).onItemUse(filler, new Ic2Player(this.worldObj), this.worldObj, this.xCoord, y + 1, this.zCoord, 0, 0.0F, 0.0F, 0.0F);
			}
		}
	}

	private boolean digDown(int y, boolean removeTipAbove)
	{
		ItemStack pipe = this.pipeSlot.consume(1, true, false);
		if (pipe != null && pipe.getItem() == Ic2Items.miningPipe.getItem())
		{
			if (y < 0)
			{
				if (removeTipAbove)
				{
					this.worldObj.setBlock(this.xCoord, y + 1, this.zCoord, StackUtil.getBlock(Ic2Items.miningPipe));
				}

				return false;
			}
			else
			{
				TileEntityMiner.MineResult result = this.mineBlock(this.xCoord, y, this.zCoord);
				if (result != TileEntityMiner.MineResult.Failed_Temp && result != TileEntityMiner.MineResult.Failed_Perm)
				{
					if (result == TileEntityMiner.MineResult.Done)
					{
						if (removeTipAbove)
						{
							this.worldObj.setBlock(this.xCoord, y + 1, this.zCoord, StackUtil.getBlock(Ic2Items.miningPipe));
						}

						this.pipeSlot.consume(1);
						this.worldObj.setBlock(this.xCoord, y, this.zCoord, StackUtil.getBlock(Ic2Items.miningPipeTip));
					}

					return true;
				}
				else
				{
					if (removeTipAbove)
					{
						this.worldObj.setBlock(this.xCoord, y + 1, this.zCoord, StackUtil.getBlock(Ic2Items.miningPipe));
					}

					return false;
				}
			}
		}
		else
		{
			return false;
		}
	}

	private TileEntityMiner.MineResult mineLevel(int y)
	{
		if (this.scannerSlot.isEmpty())
		{
			return TileEntityMiner.MineResult.Done;
		}
		else
		{
			if (this.scannedLevel != y)
			{
				this.scanRange = ((ItemScanner) this.scannerSlot.get().getItem()).startLayerScan(this.scannerSlot.get());
			}

			if (this.scanRange <= 0)
			{
				return TileEntityMiner.MineResult.Failed_Temp;
			}
			else
			{
				this.scannedLevel = y;

				for (int x = this.xCoord - this.scanRange; x <= this.xCoord + this.scanRange; ++x)
				{
					for (int z = this.zCoord - this.scanRange; z <= this.zCoord + this.scanRange; ++z)
					{
						Block block = this.worldObj.getBlock(x, y, z);
						int meta = this.worldObj.getBlockMetadata(x, y, z);
						boolean isValidTarget = false;
						if (ItemScanner.isValuable(block, meta) && this.canMine(x, y, z))
						{
							isValidTarget = true;
						}
						else if (this.pumpMode)
						{
							LiquidUtil.LiquidData result = LiquidUtil.getLiquid(this.worldObj, x, y, z);
							if (result != null && this.canPump(x, y, z))
							{
								isValidTarget = true;
							}
						}

						if (isValidTarget)
						{
							TileEntityMiner.MineResult var8 = this.mineTowards(x, y, z);
							if (var8 == TileEntityMiner.MineResult.Done)
							{
								return TileEntityMiner.MineResult.Working;
							}

							if (var8 != TileEntityMiner.MineResult.Failed_Perm)
							{
								return var8;
							}
						}
					}
				}

				return TileEntityMiner.MineResult.Done;
			}
		}
	}

	private TileEntityMiner.MineResult mineTowards(int x, int y, int z)
	{
		int dx = Math.abs(x - this.xCoord);
		int sx = this.xCoord < x ? 1 : -1;
		int dz = -Math.abs(z - this.zCoord);
		int sz = this.zCoord < z ? 1 : -1;
		int err = dx + dz;
		int cx = this.xCoord;
		int cz = this.zCoord;

		boolean isBlocking;
		do
		{
			if (cx == x && cz == z)
			{
				this.lastX = this.xCoord;
				this.lastZ = this.zCoord;
				return TileEntityMiner.MineResult.Done;
			}

			boolean isCurrentPos = cx == this.lastX && cz == this.lastZ;
			int e2 = 2 * err;
			if (e2 > dz)
			{
				err += dz;
				cx += sx;
			}
			else if (e2 < dx)
			{
				err += dx;
				cz += sz;
			}

			isBlocking = false;
			if (isCurrentPos)
			{
				isBlocking = true;
			}
			else
			{
				Block result = this.worldObj.getBlock(cx, y, cz);
				if (!result.isAir(this.worldObj, cx, y, cz))
				{
					LiquidUtil.LiquidData liquid = LiquidUtil.getLiquid(this.worldObj, cx, y, cz);
					if (liquid == null || liquid.isSource || this.pumpMode && this.canPump(x, y, z))
					{
						isBlocking = true;
					}
				}
			}
		}
		while (!isBlocking);

		TileEntityMiner.MineResult result1 = this.mineBlock(cx, y, cz);
		if (result1 == TileEntityMiner.MineResult.Done)
		{
			this.lastX = cx;
			this.lastZ = cz;
		}

		return result1;
	}

	private TileEntityMiner.MineResult mineBlock(int x, int y, int z)
	{
		Block block = this.worldObj.getBlock(x, y, z);
		boolean isAirBlock = true;
		if (!block.isAir(this.worldObj, x, y, z))
		{
			isAirBlock = false;
			LiquidUtil.LiquidData mode = LiquidUtil.getLiquid(this.worldObj, x, y, z);
			if (mode != null)
			{
				if (mode.isSource || this.pumpMode && this.canPump(x, y, z))
				{
					this.liquidX = x;
					this.liquidY = y;
					this.liquidZ = z;
					this.canProvideLiquid = true;
					return this.pumpMode ? TileEntityMiner.MineResult.Failed_Temp : TileEntityMiner.MineResult.Failed_Perm;
				}
			}
			else if (!this.canMine(x, y, z))
			{
				return TileEntityMiner.MineResult.Failed_Perm;
			}
		}

		this.canProvideLiquid = false;
		byte energyPerTick;
		short duration;
		TileEntityMiner.Mode mode1;
		if (isAirBlock)
		{
			mode1 = TileEntityMiner.Mode.MineAir;
			energyPerTick = 3;
			duration = 20;
		}
		else if (this.drillSlot.get().getItem() == Ic2Items.miningDrill.getItem())
		{
			mode1 = TileEntityMiner.Mode.MineDrill;
			energyPerTick = 6;
			duration = 200;
		}
		else
		{
			if (this.drillSlot.get().getItem() != Ic2Items.diamondDrill.getItem())
			{
				throw new IllegalStateException("invalid drill: " + this.drillSlot.get());
			}

			mode1 = TileEntityMiner.Mode.MineDDrill;
			energyPerTick = 20;
			duration = 50;
		}

		if (this.lastMode != mode1)
		{
			this.lastMode = mode1;
			this.progress = 0;
		}

		if (this.progress < duration)
		{
			if (this.energy >= (double) energyPerTick)
			{
				this.energy -= (double) energyPerTick;
				++this.progress;
				return TileEntityMiner.MineResult.Working;
			}
		}
		else if (isAirBlock || this.harvestBlock(x, y, z, block))
		{
			this.progress = 0;
			return TileEntityMiner.MineResult.Done;
		}

		return TileEntityMiner.MineResult.Failed_Temp;
	}

	private boolean harvestBlock(int x, int y, int z, Block block)
	{
		int energyCost = 2 * (this.yCoord - y);
		if (this.energy >= (double) energyCost)
		{
			if (this.drillSlot.get().getItem() == Ic2Items.miningDrill.getItem())
			{
				if (!ElectricItem.manager.use(this.drillSlot.get(), 50.0D, (EntityLivingBase) null))
				{
					return false;
				}
			}
			else
			{
				if (this.drillSlot.get().getItem() != Ic2Items.diamondDrill.getItem())
				{
					throw new IllegalStateException("invalid drill: " + this.drillSlot.get());
				}

				if (!ElectricItem.manager.use(this.drillSlot.get(), 80.0D, (EntityLivingBase) null))
				{
					return false;
				}
			}

			this.energy -= (double) energyCost;
			// TODO gamerforEA code start
			if (EventConfig.minerEvent && FakePlayerUtils.cantBreak(x, y, z, this.getOwnerFake())) return false;
			// TODO gamerforEA code end
			ArrayList<ItemStack> drops = block.getDrops(this.worldObj, x, y, z, this.worldObj.getBlockMetadata(x, y, z), 0);
			if (drops != null)
			{
				for (ItemStack drop : drops)
					this.storeDrop(drop);
			}

			this.worldObj.setBlockToAir(x, y, z);
			return true;
		}
		else
		{
			return false;
		}
	}

	private void storeDrop(ItemStack stack)
	{
		if (StackUtil.putInInventory(this, Direction.XN, stack, true) == 0)
		{
			StackUtil.dropAsEntity(this.worldObj, this.xCoord, this.yCoord, this.zCoord, stack);
		}
		else
		{
			StackUtil.putInInventory(this, Direction.XN, stack, false);
		}
	}

	public boolean canPump(int x, int y, int z)
	{
		return false;
	}

	public boolean canMine(int x, int y, int z)
	{
		Block block = this.worldObj.getBlock(x, y, z);
		if (block.isAir(this.worldObj, x, y, z))
		{
			return true;
		}
		else
		{
			int meta = this.worldObj.getBlockMetadata(x, y, z);
			return !StackUtil.equals(block, Ic2Items.miningPipe) && !StackUtil.equals(block, Ic2Items.miningPipeTip) && block != Blocks.chest ? (block instanceof IFluidBlock && this.isPumpConnected(x, y, z) ? true : ((block == Blocks.water || block == Blocks.flowing_water || block == Blocks.lava || block == Blocks.flowing_lava) && this.isPumpConnected(x, y, z) ? true : (block.getBlockHardness(this.worldObj, x, y, z) < 0.0F ? false : (block.canCollideCheck(meta, false) && block.getMaterial().isToolNotRequired() ? true : (block == Blocks.web ? true : (this.drillSlot.isEmpty() ? false : ForgeHooks.canToolHarvestBlock(block, meta, this.drillSlot.get()) || this.drillSlot.get().func_150998_b(block))))))) : false;
		}
	}

	public boolean isPumpConnected(int x, int y, int z)
	{
		return this.worldObj.getTileEntity(this.xCoord, this.yCoord + 1, this.zCoord) instanceof TileEntityPump && ((TileEntityPump) this.worldObj.getTileEntity(this.xCoord, this.yCoord + 1, this.zCoord)).pump(x, y, z, true, this) != null ? true : (this.worldObj.getTileEntity(this.xCoord, this.yCoord - 1, this.zCoord) instanceof TileEntityPump && ((TileEntityPump) this.worldObj.getTileEntity(this.xCoord, this.yCoord - 1, this.zCoord)).pump(x, y, z, true, this) != null ? true : (this.worldObj.getTileEntity(this.xCoord + 1, this.yCoord, this.zCoord) instanceof TileEntityPump && ((TileEntityPump) this.worldObj.getTileEntity(this.xCoord + 1, this.yCoord, this.zCoord)).pump(x, y, z, true, this) != null ? true : (this.worldObj.getTileEntity(this.xCoord - 1, this.yCoord, this.zCoord) instanceof TileEntityPump && ((TileEntityPump) this.worldObj.getTileEntity(this.xCoord - 1, this.yCoord, this.zCoord)).pump(x, y, z, true, this) != null ? true : (this.worldObj.getTileEntity(this.xCoord, this.yCoord, this.zCoord + 1) instanceof TileEntityPump && ((TileEntityPump) this.worldObj.getTileEntity(this.xCoord, this.yCoord, this.zCoord + 1)).pump(x, y, z, true, this) != null ? true : this.worldObj.getTileEntity(this.xCoord, this.yCoord, this.zCoord - 1) instanceof TileEntityPump && ((TileEntityPump) this.worldObj.getTileEntity(this.xCoord, this.yCoord, this.zCoord - 1)).pump(x, y, z, true, this) != null))));
	}

	public boolean isAnyPumpConnected()
	{
		return this.worldObj.getTileEntity(this.xCoord, this.yCoord + 1, this.zCoord) instanceof TileEntityPump ? true : (this.worldObj.getTileEntity(this.xCoord, this.yCoord - 1, this.zCoord) instanceof TileEntityPump ? true : (this.worldObj.getTileEntity(this.xCoord + 1, this.yCoord, this.zCoord) instanceof TileEntityPump ? true : (this.worldObj.getTileEntity(this.xCoord - 1, this.yCoord, this.zCoord) instanceof TileEntityPump ? true : (this.worldObj.getTileEntity(this.xCoord, this.yCoord, this.zCoord + 1) instanceof TileEntityPump ? true : this.worldObj.getTileEntity(this.xCoord, this.yCoord, this.zCoord - 1) instanceof TileEntityPump))));
	}

	public String getInventoryName()
	{
		return "Miner";
	}

	public ContainerBase<TileEntityMiner> getGuiContainer(EntityPlayer entityPlayer)
	{
		return new ContainerMiner(entityPlayer, this);
	}

	@SideOnly(Side.CLIENT)
	public GuiScreen getGui(EntityPlayer entityPlayer, boolean isAdmin)
	{
		return new GuiMiner(new ContainerMiner(entityPlayer, this));
	}

	public void onGuiClosed(EntityPlayer entityPlayer)
	{
	}

	public void onNetworkUpdate(String field)
	{
		if (field.equals("active") && this.prevActive != this.getActive())
		{
			if (this.audioSource == null)
			{
				this.audioSource = IC2.audioManager.createSource(this, PositionSpec.Center, "Machines/MinerOp.ogg", true, false, IC2.audioManager.getDefaultVolume());
			}

			if (this.getActive())
			{
				if (this.audioSource != null)
				{
					this.audioSource.play();
				}
			}
			else if (this.audioSource != null)
			{
				this.audioSource.stop();
			}
		}

		super.onNetworkUpdate(field);
	}

	public double getEnergy()
	{
		return this.energy;
	}

	public boolean useEnergy(double amount)
	{
		if (this.energy >= amount)
		{
			this.energy -= amount;
			return true;
		}
		else
		{
			return false;
		}
	}

	public void setRedstonePowered(boolean redstone)
	{
	}

	public List<ItemStack> getCompatibleUpgradeList()
	{
		ArrayList itemstack = new ArrayList();
		itemstack.add(Ic2Items.ejectorUpgrade);
		itemstack.add(Ic2Items.pullingUpgrade);
		return itemstack;
	}

	static enum MineResult
	{
		Working, Done, Failed_Temp, Failed_Perm;
	}

	static enum Mode
	{
		None, Withdraw, MineAir, MineDrill, MineDDrill;
	}
}