package mcopt.metal.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * A read-only reader of a RocksDB database directory, enough to list a column family's live keys and read their values
 * (LodVoxyImport reads Voxy's saved terrain with it; the game ships no RocksDB). As RocksDB recovers: the MANIFEST names
 * the column families, each one's live table files (*.sst: blocks plain or zstd-compressed) and the log number its tables
 * cover; the write-ahead logs from there on (*.log: RocksDB doesn't flush them into tables when the database closes) are
 * replayed over them; per key the version with the highest sequence number wins (a deletion drops the key). Files a crash
 * left behind aren't read. No locking: the database must not be open meanwhile (its writer isn't running).
 *
 * The formats: RocksDB 10.2's (github.com/facebook/rocksdb: table/format.cc, table/block_based/block.cc, db/log_format.h,
 * db/write_batch.cc, db/version_edit.cc).
 */
final class LodRocks implements AutoCloseable {
	/** A key's bytes, ordered as RocksDB's default comparator orders them (unsigned, lexicographic). */
	record Key(byte[] bytes) implements Comparable<Key> {
		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && Arrays.equals(this.bytes, k.bytes);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(this.bytes);
		}

		@Override
		public int compareTo(Key o) {
			return Arrays.compareUnsigned(this.bytes, o.bytes);
		}
	}

	/** A live value: read when asked (from its table file) or kept (from a log, or a compressed block). */
	interface Value {
		byte[] bytes() throws IOException;
	}

	private static final long TABLE_MAGIC = 0x88e241b785f4cff7L, LEGACY_TABLE_MAGIC = 0xdb4775248b80fb57L;
	private static final int TYPE_DELETION = 0x0, TYPE_VALUE = 0x1, TYPE_SINGLE_DELETION = 0x7, TYPE_DELETION_TS = 0x14;

	private final Path dir;
	private final List<FileChannel> open = new ArrayList<>();
	/** Column family ids by name (the MANIFEST's). */
	private final Map<String, Integer> families = new HashMap<>();

	LodRocks(Path dir) throws IOException {
		this.dir = dir;
		this.readManifest();
	}

	@Override
	public void close() throws IOException {
		for (FileChannel c : this.open) c.close();
		this.open.clear();
	}

	private record Entry(long seq, int type, Value value) {
	}

	/**
	 * A column family's live keys (those `keep` takes) and their values, in key order. Empty when there's no such family.
	 */
	NavigableMap<Key, Value> family(String name, Predicate<byte[]> keep) throws IOException {
		Integer id = this.families.get(name);
		TreeMap<Key, Value> out = new TreeMap<>();
		if (id == null) return out;
		Map<Key, Entry> latest = new HashMap<>();
		// the family's live tables, deepest level first and the newest last (at equal sequence numbers, possible only at 0
		// after a compaction to the bottom, the shallower and newer wins), then its logs from its log number on
		List<Map.Entry<Long, Integer>> live = new ArrayList<>(this.files.getOrDefault(id, Map.of()).entrySet());
		live.sort((a, b) -> a.getValue() != b.getValue().intValue() ? Integer.compare(b.getValue(), a.getValue()) : Long.compare(a.getKey(), b.getKey()));
		for (var f : live) {
			Path t = this.dir.resolve(String.format("%06d.sst", f.getKey()));
			if (!Files.isRegularFile(t)) t = this.dir.resolve(String.format("%06d.ldb", f.getKey()));
			if (Files.isRegularFile(t)) this.table(t, id, keep, latest);
		}
		long minLog = this.logNumbers.getOrDefault(id, 0L);
		List<long[]> logs = new ArrayList<>();
		try (var s = Files.list(this.dir)) {
			for (Path p : s.toList()) {
				String n = p.getFileName().toString();
				if (!n.endsWith(".log") || n.startsWith("LOG")) continue;
				try {
					long num = Long.parseLong(n.substring(0, n.length() - 4));
					if (num >= minLog) logs.add(new long[] {num});
				} catch (NumberFormatException e) {
					// (not a log of the database's)
				}
			}
		}
		logs.sort((a, b) -> Long.compare(a[0], b[0]));
		for (long[] l : logs) this.log(this.dir.resolve(String.format("%06d.log", l[0])), id, keep, latest);
		for (var e : latest.entrySet()) {
			if (e.getValue().type == TYPE_VALUE) out.put(e.getKey(), e.getValue().value);
		}
		return out;
	}

	NavigableMap<Key, Value> family(String name) throws IOException {
		return this.family(name, k -> true);
	}

	private static void put(Map<Key, Entry> latest, byte[] key, long seq, int type, Value v) {
		Key k = new Key(key);
		Entry old = latest.get(k);
		if (old == null || seq >= old.seq) latest.put(k, new Entry(seq, type, v));
	}

	// ---- the MANIFEST: column families, their live table files and log numbers ----

	/** Per family id: its live table files (number -> level) and the log number its tables cover the logs up to. */
	private final Map<Integer, Map<Long, Integer>> files = new HashMap<>();
	private final Map<Integer, Long> logNumbers = new HashMap<>();

	private void readManifest() throws IOException {
		Path current = this.dir.resolve("CURRENT");
		if (!Files.isRegularFile(current)) throw new IOException("not a RocksDB database: " + this.dir);
		Path manifest = this.dir.resolve(Files.readString(current, StandardCharsets.US_ASCII).strip());
		this.families.put("default", 0);
		for (byte[] rec : records(Files.readAllBytes(manifest))) {
			ByteBuffer b = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
			int cf = 0;
			String added = null;
			boolean dropped = false;
			long log = -1;
			List<long[]> deleted = new ArrayList<>(), added4 = new ArrayList<>();
			while (b.hasRemaining()) {
				int tag = (int) varint(b);
				switch (tag) {
					case 1 -> skipString(b);                                           // comparator
					case 2 -> log = varint(b);                                         // log number
					case 3, 4, 9, 10 -> varint(b);                                     // next file, last sequence, prev log, min log
					case 5 -> { varint(b); skipString(b); }                           // compact cursor
					case 6 -> deleted.add(new long[] {varint(b), varint(b)});          // deleted file: level, number
					case 7 -> { added4.add(new long[] {varint(b), varint(b)}); varint(b); skipString(b); skipString(b); }
					case 100 -> { added4.add(new long[] {varint(b), varint(b)}); varint(b); skipString(b); skipString(b); varint(b); varint(b); }
					case 102 -> { added4.add(new long[] {varint(b), varint(b)}); varint(b); varint(b); skipString(b); skipString(b); varint(b); varint(b); }
					case 103 -> {                                                      // new file 4: then custom fields
						added4.add(new long[] {varint(b), varint(b)});
						varint(b); skipString(b); skipString(b); varint(b); varint(b);
						customFields(b);
					}
					case 200 -> cf = (int) varint(b);
					case 201 -> added = string(b);
					case 202 -> dropped = true;
					case 203, 300 -> varint(b);                                        // max column family, in atomic group
					case 400 -> { varint(b); varint(b); varint(b); skipString(b); skipString(b); customFields(b); }
					case 401 -> { varint(b); varint(b); varint(b); customFields(b); }
					default -> {
						// (tags from 8192 on may be ignored: their length comes first)
						if ((tag & (1 << 13)) == 0) throw new IOException("unknown MANIFEST tag " + tag);
						skipString(b);
					}
				}
			}
			if (added != null) this.families.put(added, cf);
			int id = cf;
			if (dropped) {
				this.families.values().removeIf(v -> v == id);
				this.files.remove(id);
				continue;
			}
			// (deletions first, then additions, as RocksDB applies an edit)
			Map<Long, Integer> live = this.files.computeIfAbsent(id, k -> new HashMap<>());
			for (long[] d : deleted) live.remove(d[1]);
			for (long[] a : added4) live.put(a[1], (int) a[0]);
			if (log >= 0) this.logNumbers.merge(id, log, Math::max);
		}
	}

	private static void customFields(ByteBuffer b) {
		while (true) {
			int t = (int) varint(b);
			if (t == 1) return;   // kTerminate
			skipString(b);
		}
	}

	// ---- log files (the write-ahead logs, the MANIFEST): 32 KB blocks of fragments ----

	/**
	 * A log file's records, reassembled from their fragments. A block's zero header (preallocated space) skips the rest of
	 * the block; reading stops at the first record cut short or whose checksum is wrong (a torn tail).
	 */
	static List<byte[]> records(byte[] f) {
		List<byte[]> out = new ArrayList<>();
		java.io.ByteArrayOutputStream partial = null;
		java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
		int at = 0;
		while (at < f.length) {
			int inBlock = at % 32768, left = 32768 - inBlock;
			if (left < 7) {
				at += left;
				continue;
			}
			if (at + 7 > f.length) break;
			int len = (f[at + 4] & 0xFF) | (f[at + 5] & 0xFF) << 8, type = f[at + 6] & 0xFF;
			if (type == 0 && len == 0) {
				at += left;
				continue;
			}
			int header = type >= 5 && type <= 8 || type == 11 || type == 131 ? 11 : 7;
			if (at + header + len > f.length || header + len > left) break;
			// the checksum: masked CRC32C of the type byte (and a recyclable header's log number) and the payload
			crc.reset();
			crc.update(f, at + 6, header - 6 + len);
			int c = (int) crc.getValue();
			int masked = ((c >>> 15) | (c << 17)) + 0xa282ead8;
			int stored = (f[at] & 0xFF) | (f[at + 1] & 0xFF) << 8 | (f[at + 2] & 0xFF) << 16 | (f[at + 3] & 0xFF) << 24;
			if (masked != stored) break;
			int from = at + header;
			at += header + len;
			int kind = type >= 5 && type <= 8 ? type - 4 : type;
			switch (kind) {
				case 1 -> {
					out.add(Arrays.copyOfRange(f, from, from + len));
					partial = null;
				}
				case 2 -> {
					partial = new java.io.ByteArrayOutputStream();
					partial.write(f, from, len);
				}
				case 3 -> {
					if (partial != null) partial.write(f, from, len);
				}
				case 4 -> {
					if (partial != null) {
						partial.write(f, from, len);
						out.add(partial.toByteArray());
					}
					partial = null;
				}
				default -> {
					// (compression, timestamp-size and predecessor records: none written with the options read here)
				}
			}
		}
		return out;
	}

	/** A write-ahead log's batches: their puts and deletions of family `cf`. */
	private void log(Path file, int cf, Predicate<byte[]> keep, Map<Key, Entry> latest) throws IOException {
		for (byte[] rec : records(Files.readAllBytes(file))) {
			if (rec.length < 12) continue;
			ByteBuffer b = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
			long seq = b.getLong(0);
			b.position(12);
			try {
				while (b.hasRemaining()) {
					int tag = b.get() & 0xFF;
					int family = 0;
					switch (tag) {
						case 0x4, 0x5, 0x6, 0x8, 0xE, 0x10, 0x17, 0x19 -> family = (int) varint(b);
						default -> {
						}
					}
					switch (tag) {
						case 0x1, 0x5 -> {                                                 // value
							byte[] k = bytes(b), v = bytes(b);
							if (family == cf && keep.test(k)) put(latest, k, seq, TYPE_VALUE, () -> v);
							seq++;
						}
						case 0x0, 0x4, 0x7, 0x8 -> {                                       // deletion, single deletion
							byte[] k = bytes(b);
							if (family == cf && keep.test(k)) put(latest, k, seq, TYPE_DELETION, () -> new byte[0]);
							seq++;
						}
						case 0x2, 0x6, 0x10, 0x11, 0x16, 0x17, 0x18, 0x19 -> {             // merge, blob index, entity, preferred seqno: not read
							bytes(b);
							bytes(b);
							seq++;
						}
						case 0xE, 0xF -> {                                                 // range deletion: not written by these writers
							bytes(b);
							bytes(b);
							seq++;
						}
						case 0x3 -> bytes(b);                                              // log data
						case 0x9, 0xA, 0xB, 0xC, 0x12, 0x13 -> {                           // transactions' markers
							if (tag != 0x9 && tag != 0x12 && tag != 0x13) bytes(b);
						}
						case 0xD -> {
						}
						default -> throw new IOException("unknown write batch tag " + tag);
					}
				}
			} catch (IOException | RuntimeException e) {
				// (a batch that doesn't parse: what came before it stays)
			}
		}
	}

	// ---- table files ----

	private record Handle(long offset, long size) {
	}

	/** A table file's entries of family `cf` (a table holds one family's: its properties name it). */
	private void table(Path file, int cf, Predicate<byte[]> keep, Map<Key, Entry> latest) throws IOException {
		FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
		boolean kept = false;
		try {
			long size = ch.size();
			if (size < 53) return;
			ByteBuffer foot = read(ch, size - 53, 53);
			long magic = foot.getLong(45);
			if (magic != TABLE_MAGIC) return;   // (legacy or other table formats: not written by these writers)
			int version = foot.getInt(41);
			ByteBuffer meta;
			Handle indexHandle;
			if (version >= 6) {
				// the metaindex sits right before the footer, its size in the footer; the index's handle is in it
				int metaSize = foot.getInt(1 + 4 + 4 + 4);
				Handle mh = new Handle(size - 53 - 5 - metaSize, metaSize);
				meta = this.block(ch, mh);
				indexHandle = null;
				for (byte[][] kv : entries(meta, false)) {
					if (new String(kv[0], StandardCharsets.UTF_8).equals("rocksdb.index")) indexHandle = handle(ByteBuffer.wrap(kv[1]).order(ByteOrder.LITTLE_ENDIAN));
				}
				if (indexHandle == null) throw new IOException(file + ": no index");
			} else {
				ByteBuffer h = foot.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(1);
				Handle mh = handle(h);
				indexHandle = handle(h);
				meta = this.block(ch, mh);
			}
			// the properties: the family, the index's kind and encoding
			Map<String, byte[]> props = new HashMap<>();
			for (byte[][] kv : entries(meta, false)) {
				if (new String(kv[0], StandardCharsets.UTF_8).equals("rocksdb.properties")) {
					for (byte[][] p : entries(this.block(ch, handle(ByteBuffer.wrap(kv[1]).order(ByteOrder.LITTLE_ENDIAN))), false)) {
						props.put(new String(p[0], StandardCharsets.UTF_8), p[1]);
					}
				}
			}
			byte[] fam = props.get("rocksdb.column.family.id");
			if (fam == null || varint(ByteBuffer.wrap(fam)) != cf) return;
			byte[] it = props.get("rocksdb.block.based.table.index.type");
			int indexType = it != null && it.length == 4 ? ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).getInt() : 0;
			byte[] de = props.get("rocksdb.index.value.is.delta.encoded");
			boolean delta = de != null && varint(ByteBuffer.wrap(de)) != 0;
			// the data blocks, through the index (and its partitions, a two-level index)
			List<Handle> data = new ArrayList<>();
			for (Handle h : indexHandles(this.block(ch, indexHandle), delta)) {
				if (indexType == 2) data.addAll(indexHandles(this.block(ch, h), delta));
				else data.add(h);
			}
			for (Handle h : data) {
				Block blk = this.rawBlock(ch, h);
				for (Item e : dataEntries(blk.buf)) {
					byte[] ik = e.key;
					if (ik.length < 8) continue;
					byte[] k = Arrays.copyOf(ik, ik.length - 8);
					long trailer = ByteBuffer.wrap(ik, ik.length - 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
					int type = (int) (trailer & 0xFF);
					long seq = trailer >>> 8;
					if (!keep.test(k)) continue;
					if (type == TYPE_VALUE) {
						Value v;
						if (blk.compressed) {
							byte[] copy = new byte[e.valueLen];
							blk.buf.get(e.valueAt, copy);
							v = () -> copy;
						} else {
							// read when asked, from the file (kept open)
							long at = h.offset + e.valueAt;
							int len = e.valueLen;
							v = () -> {
								ByteBuffer b = read(ch, at, len);
								byte[] out = new byte[len];
								b.get(out);
								return out;
							};
							kept = true;
						}
						put(latest, k, seq, TYPE_VALUE, v);
					} else if (type == TYPE_DELETION || type == TYPE_SINGLE_DELETION || type == TYPE_DELETION_TS) {
						put(latest, k, seq, TYPE_DELETION, () -> new byte[0]);
					} else {
						// (merges, blobs, wide columns: never written by the writers read here, and not readable as values)
						throw new IOException(file.getFileName() + ": entry type " + type + " not read");
					}
				}
			}
		} finally {
			if (kept) this.open.add(ch);
			else ch.close();
		}
	}

	private static ByteBuffer read(FileChannel ch, long at, int len) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(len);
		while (b.hasRemaining()) {
			int n = ch.read(b, at + b.position());
			if (n < 0) throw new IOException("unexpected end of file");
		}
		return b.flip().order(ByteOrder.LITTLE_ENDIAN);
	}

	private record Block(ByteBuffer buf, boolean compressed) {
	}

	/** A block's contents (decompressed): its trailer's type byte says how it is stored. */
	private Block rawBlock(FileChannel ch, Handle h) throws IOException {
		if (h.size > Integer.MAX_VALUE - 5) throw new IOException("block too large");
		ByteBuffer b = read(ch, h.offset, (int) h.size + 1);
		int type = b.get((int) h.size) & 0xFF;
		b.limit((int) h.size);
		if (type == 0) return new Block(b.slice().order(ByteOrder.LITTLE_ENDIAN), false);
		if (type != 7) throw new IOException("block compression " + type + " not read");
		// zstd: the decompressed size as a varint, then a frame
		long n = varint(b);
		byte[] src = new byte[b.remaining()];
		b.get(src);
		byte[] out = LodZstd.get().decompress(src, (int) Math.min(Integer.MAX_VALUE - 16, Math.max(n, 1)));
		return new Block(ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN), true);
	}

	private ByteBuffer block(FileChannel ch, Handle h) throws IOException {
		return this.rawBlock(ch, h).buf;
	}

	private static Handle handle(ByteBuffer b) {
		return new Handle(varint(b), varint(b));
	}

	private record Item(byte[] key, int valueAt, int valueLen) {
	}

	/** The restart array's end: a data block's last word packs its index type in bit 31 (1: a hash index before it). */
	private static int entriesEnd(ByteBuffer b, boolean data) {
		int n = b.limit();
		int packed = b.getInt(n - 4);
		// (a block over 64 KiB never has the hash index: its word is the count alone)
		boolean hash = data && n <= 65536 && packed < 0;
		int restarts = hash ? packed & 0x7FFFFFFF : packed;
		int end = n - 4;
		if (hash) {
			int buckets = b.getShort(n - 6) & 0xFFFF;
			end = n - 6 - buckets;
		}
		return end - 4 * restarts;
	}

	/** A block's entries as (key, value) with prefix-compressed keys (metaindex, properties). */
	private static List<byte[][]> entries(ByteBuffer b, boolean data) {
		List<byte[][]> out = new ArrayList<>();
		for (Item it : items(b, data)) {
			byte[] v = new byte[it.valueLen];
			b.get(it.valueAt, v);
			out.add(new byte[][] {it.key, v});
		}
		return out;
	}

	private static List<Item> dataEntries(ByteBuffer b) {
		return items(b, true);
	}

	private static List<Item> items(ByteBuffer b, boolean data) {
		List<Item> out = new ArrayList<>();
		int end = entriesEnd(b, data);
		ByteBuffer r = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		r.position(0);
		byte[] last = new byte[0];
		while (r.position() < end) {
			int shared = (int) varint(r), nonShared = (int) varint(r);
			int vlen = (int) varint(r);
			byte[] key = new byte[shared + nonShared];
			System.arraycopy(last, 0, key, 0, shared);
			r.get(key, shared, nonShared);
			int at = r.position();
			r.position(at + vlen);
			out.add(new Item(key, at, vlen));
			last = key;
		}
		return out;
	}

	/**
	 * An index block's block handles, in order. delta: values without a length, a full handle where the key shares nothing
	 * with the one before (a restart), else the size's change (zigzag), the block right after the one before.
	 */
	private static List<Handle> indexHandles(ByteBuffer b, boolean delta) {
		List<Handle> out = new ArrayList<>();
		int end = entriesEnd(b, false);
		ByteBuffer r = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		r.position(0);
		Handle prev = null;
		while (r.position() < end) {
			int shared = (int) varint(r), nonShared = (int) varint(r);
			if (!delta) {
				int vlen = (int) varint(r);
				r.position(r.position() + nonShared);
				int at = r.position();
				prev = handle(r);
				r.position(at + vlen);
			} else {
				r.position(r.position() + nonShared);
				if (shared == 0 || prev == null) {
					prev = handle(r);
				} else {
					long size = prev.size + zigzag(varint(r));
					prev = new Handle(prev.offset + prev.size + 5, size);
				}
			}
			out.add(prev);
		}
		return out;
	}

	private static long zigzag(long v) {
		return (v >>> 1) ^ -(v & 1);
	}

	// ---- encodings ----

	private static long varint(ByteBuffer b) {
		long v = 0;
		for (int shift = 0; shift < 64; shift += 7) {
			int c = b.get() & 0xFF;
			v |= (long) (c & 0x7F) << shift;
			if ((c & 0x80) == 0) return v;
		}
		throw new IllegalStateException("bad varint");
	}

	private static byte[] bytes(ByteBuffer b) {
		int n = (int) varint(b);
		byte[] out = new byte[n];
		b.get(out);
		return out;
	}

	private static String string(ByteBuffer b) {
		return new String(bytes(b), StandardCharsets.UTF_8);
	}

	private static void skipString(ByteBuffer b) {
		int n = (int) varint(b);
		b.position(b.position() + n);
	}
}
