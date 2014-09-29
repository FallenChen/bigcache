package com.ctriposs.quickcache.storage;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.ctriposs.quickcache.CacheConfig.StartMode;
import com.ctriposs.quickcache.CacheConfig.StorageMode;
import com.ctriposs.quickcache.IBlock;
import com.ctriposs.quickcache.utils.FileUtil;

public class StorageManager {
	/**
	 * The Constant DEFAULT_CAPACITY_PER_BLOCK.
	 */
	public final static int DEFAULT_CAPACITY_PER_BLOCK = 128 * 1024 * 1024; // 128M

	/** 
	 * The Constant DEFAULT_INITIAL_NUMBER_OF_BLOCKS. 
	 */
	public final static int DEFAULT_INITIAL_NUMBER_OF_BLOCKS = 8; // 1GB total

	/**
	 * The Constant DEFAULT_MEMORY_SIZE.
	 */
	public static final long DEFAULT_MAX_OFFHEAP_MEMORY_SIZE = 2 * 1024 * 1024 * 1024L; // Unit: GB
	
	/** 
	 * keep track of the number of blocks allocated 
	 */
	private final AtomicInteger blockCount = new AtomicInteger(0);
	
	/** The active storage block change lock. */
	private final Lock activeBlockChangeLock = new ReentrantLock();

	/**
	 * Current active block for appending new cache data
	 */
	private volatile IBlock activeBlock;
	
	/**
	 *  A list of used storage blocks
	 */
	private final Queue<IBlock> usedBlocks = new ConcurrentLinkedQueue<IBlock>();
	
	/**
	 *  A queue of free storage blocks which is a priority queue and always return the block with smallest index.
	 */
	private final Queue<IBlock> freeBlocks = new PriorityBlockingQueue<IBlock>();
	
	/**
	 * Current storage mode
	 */
	private final StorageMode storageMode;

	/**
	 * Current start mode
	 */
	private final StartMode startMode;
	
	/**
	 * The number of memory blocks allow to be created.
	 */
	private int allowedOffHeapModeBlockCount;
	
	/**
	 * Directory for cache data store
	 */
	private final String dir;
	
	/**
	 * The capacity per block in bytes
	 */
	private final int capacityPerBlock;
	
	/** 
	 * dirty ratio which controls block recycle 
	 */
    public static double dirtyRatioThreshold;
	
	
	public StorageManager(String dir, int capacityPerBlock, int initialNumberOfBlocks, StorageMode storageMode,
			long maxOffHeapMemorySize, double dirtyRatioThreshold, StartMode startMode) throws IOException {
		this.dirtyRatioThreshold = dirtyRatioThreshold;
		
		if (storageMode != StorageMode.PureFile||storageMode != StorageMode.MapFile) {
			this.allowedOffHeapModeBlockCount = (int)(maxOffHeapMemorySize / capacityPerBlock);
		} else {
			this.allowedOffHeapModeBlockCount = 0;
		}

		this.storageMode = storageMode;	
		this.startMode = startMode;
		this.capacityPerBlock = capacityPerBlock;
		this.dir = dir;
		initializeBlocks(new File(dir), initialNumberOfBlocks);
	}
	
	private void initializeBlocks(File directory, int initialNumberOfBlocks) throws IOException {
		List<File> list = null;
		switch (startMode) {
            case None:
                FileUtil.deleteDirectory(directory);
                list = FileUtil.listFiles(directory);
                break;
            case File:
                list = FileUtil.listFiles(directory);
                for(File file:list) {
                    IBlock block = new StorageBlock(file, blockCount.incrementAndGet(), this.capacityPerBlock, storageMode);
                    block.getAllValidMeta();
                    if(block.getMetaCount() == 0) {
                        freeBlocks.offer(block);
                    }else {
                        usedBlocks.add(block);
                    }

                }
                break;
		}
				
		for (int i = list.size(); i < initialNumberOfBlocks; i++) {
			IBlock block = createNewBlock(i);
			freeBlocks.offer(block);
			blockCount.incrementAndGet();
		}		
		this.activeBlock = freeBlocks.poll();
		if(this.activeBlock == null) {
			this.activeBlock = new StorageBlock(dir, blockCount.incrementAndGet(), this.capacityPerBlock, storageMode);
		}
		this.activeBlock.active();
	}
	
	public void loadPointerMap(ConcurrentMap<WrapperKey, Pointer> map)throws IOException {
        synchronized (this) {
        	ConcurrentMap<WrapperKey, Long> deleteMap = new ConcurrentHashMap<WrapperKey, Long>();
        	Iterator<IBlock> it = usedBlocks.iterator();
        	while (it.hasNext()) {
        		IBlock block = it.next();
        		
        		for(int index=0;index<Meta.MAX_META_COUNT;index++) {
        			Meta meta = block.readMeta(index);
        			if(meta.getLastAccessTime()==0) {
        				break;
        			}
					Item item = block.readItem(meta);
					WrapperKey wKey = new WrapperKey(item.getKey());
					
					if(meta.getTtl()==0) {
						deleteMap.put(wKey, meta.getLastAccessTime());
					}else {
						Long accesstime = deleteMap.get(wKey);
						Pointer newPointer = new Pointer(block, meta.getMetaOffset(), item.getKey().length, item.getValue().length, meta.getTtl(),meta.getLastAccessTime());
						if(accesstime==null) {
							
							map.put(wKey, newPointer);
						}else {
							if(accesstime<newPointer.getLastAccessTime()) {
								map.put(wKey, newPointer);
							}
						}
						
					}
        		}        		
			}
        }
	}
	
	private IBlock createNewBlock(int index) throws IOException {
		if (this.allowedOffHeapModeBlockCount > 0) {
			IBlock block = new StorageBlock(this.dir, index, this.capacityPerBlock, this.storageMode);
			this.allowedOffHeapModeBlockCount--;
			return block;
		} else {
			return new StorageBlock(this.dir, index, this.capacityPerBlock, StorageMode.PureFile);
		}
	}


	public void close() throws IOException {
		for(IBlock usedBlock : usedBlocks) {
			usedBlock.close();
		}
        usedBlocks.clear();
		for(IBlock freeBlock : freeBlocks) {
			freeBlock.close();
		}
        freeBlocks.clear();
		activeBlock.close();
	}
	
	public void clean() {
        synchronized (this) {
            Iterator<IBlock> it = usedBlocks.iterator();
            while(it.hasNext()) {
                IBlock block = it.next();
                if (block.getUsed() == 0) {
                    // we will not allocating memory from it any more and it is used by nobody.
                    block.free();
                    freeBlocks.add(block);
                    it.remove();
                }
            }
        }
	}

    /**
     * Store the value
     * @param key the key
     * @param value the data
     * @param ttl time-to-live
     * @return a pointer
     * @throws IOException
     */
	public Pointer store(byte[] key, byte[] value, long ttl) throws IOException {
		Pointer pointer = activeBlock.store(key, value, ttl);
		if (pointer != null) {// success
			return pointer; 
		}else { // overflow
			activeBlockChangeLock.lock(); 
			try {
				// other thread may have changed the active block
				pointer = activeBlock.store(key,value,ttl);
				if (pointer != null) {// success
					return pointer; 
				} else { // still overflow
					IBlock freeBlock = this.freeBlocks.poll();
					if (freeBlock == null) { // create a new one
						freeBlock = createNewBlock(this.blockCount.getAndIncrement());
					}
					pointer = freeBlock.store(key,value,ttl);
					this.usedBlocks.add(this.activeBlock);
					this.activeBlock.used();
					this.activeBlock = freeBlock;
					this.activeBlock.active();
					return pointer;
				}
				
			} finally {
				activeBlockChangeLock.unlock();
			}
		}
	}



	public byte[] retrieve(Pointer pointer) throws IOException {		
		return pointer.getBlock().retrieve(pointer);
	}

	
	public byte[] remove(Pointer pointer) throws IOException {

		return pointer.getBlock().remove(pointer);
	}

	
	public int markDirty(Pointer pointer) {
		return pointer.getBlock().markDirty(pointer);
	}

    /**
     * Get the capacity of all used storage
     * @return used storage capacity
     */
	public long getUsed() {
		long usedStorage = 0;
		for(IBlock block : usedBlocks) {
			usedStorage += block.getUsed();
		}
		return usedStorage + activeBlock.getUsed();
	}

	public void free() {
		for(IBlock storageBlock : usedBlocks) {
			storageBlock.free();
			this.freeBlocks.offer(storageBlock);
		}
		usedBlocks.clear();
		this.activeBlock.free();
	}

    /**
     * Get the dirty storage size
     * @return dirty storage size
     */
	public long getDirty() {
		long dirtyStorage = 0;
		for(IBlock block : usedBlocks) {
			dirtyStorage += block.getDirty();
		}
		return dirtyStorage + activeBlock.getDirty();
	}

    /**
     * Get the capacity of all blocks (active, used, free)
     * @return total capacity
     */
	public long getCapacity() {
        long totalCapacity = 0;
        for(IBlock block : getAllBlocks()) {
            totalCapacity += block.getCapacity();
        }
		return totalCapacity;
	}

    /**
     * Get the proportion of dirty storage within total capacity
     * @return the dirty ratio
     */
	public double getDirtyRatio() {
		double d = (getDirty() * 1.0) / getCapacity();
        return d;
	}
	
	public int getFreeBlockCount() {
		return freeBlocks.size();
	}
	
	public int getUsedBlockCount() {
		return usedBlocks.size();
	}
	
    private Set<IBlock> getAllBlocks() {
        Set<IBlock> allBlocks = new HashSet<IBlock>();
        allBlocks.addAll(usedBlocks);
        allBlocks.addAll(freeBlocks);
        allBlocks.add(activeBlock);
        return allBlocks;
    }
    
    public Set<IBlock> getDirtyBlocks(){
		Set<IBlock> set = new HashSet<IBlock>();
		for(IBlock block:usedBlocks) {
			if(dirtyRatioThreshold < block.getDirtyRatio()) {
				set.add(block);
			}
		}
		return set;
    }
	
	public int getTotalBlockCount() {
		return getAllBlocks().size();
	}

}
