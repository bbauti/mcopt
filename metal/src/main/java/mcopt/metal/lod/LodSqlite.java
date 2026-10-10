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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A read-only reader of SQLite database files (https://www.sqlite.org/fileformat2.html), enough to walk one table's rows
 * (LodDhImport reads Distant Horizons' saved terrain with it; the game ships no SQLite), with the pages the write-ahead log
 * ("-wal") holds committed over the file's. Only rowid tables; no indexes, no locking: the file must not be written meanwhile.
 */
final class LodSqlite implements AutoCloseable {
	private final FileChannel db;
	private final @Nullable FileChannel wal;
	private final int pageSize, usable;
	private final java.nio.charset.Charset text;
	/** Pages the log holds a committed copy of: the copy's offset in the log. */
	private final Map<Integer, Long> walPages = new HashMap<>();

	LodSqlite(Path file) throws IOException {
		this.db = FileChannel.open(file, StandardOpenOption.READ);
		try {
			ByteBuffer h = ByteBuffer.allocate(100);
			readFully(this.db, h, 0);
			if (!new String(h.array(), 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3")) throw new IOException("not an SQLite database: " + file);
			this.pageSize = h.getShort(16) == 1 ? 65536 : h.getShort(16) & 0xFFFF;
			if (this.pageSize < 512 || Integer.bitCount(this.pageSize) != 1) throw new IOException("bad page size " + this.pageSize);
			this.usable = this.pageSize - (h.get(20) & 0xFF);
			this.text = h.getInt(56) == 2 ? StandardCharsets.UTF_16LE : h.getInt(56) == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8;
			Path walFile = file.resolveSibling(file.getFileName() + "-wal");
			this.wal = Files.isRegularFile(walFile) && Files.size(walFile) > 32 ? FileChannel.open(walFile, StandardOpenOption.READ) : null;
			if (this.wal != null) this.readWal(this.wal);
		} catch (IOException | RuntimeException e) {
			this.db.close();
			throw e;
		}
	}

	@Override
	public void close() throws IOException {
		this.db.close();
		if (this.wal != null) this.wal.close();
	}

	private static void readFully(FileChannel ch, ByteBuffer b, long at) throws IOException {
		b.clear();
		while (b.hasRemaining()) {
			int n = ch.read(b, at + b.position());
			if (n < 0) throw new IOException("unexpected end of file");
		}
		b.flip();
	}

	/** The frames of committed transactions whose salts and checksums hold, latest copy of each page winning. */
	private void readWal(FileChannel w) throws IOException {
		ByteBuffer h = ByteBuffer.allocate(32);
		readFully(w, h, 0);
		if ((h.getInt(0) & ~1) != 0x377f0682 || h.getInt(8) != this.pageSize) return;
		boolean bigEndian = (h.getInt(0) & 1) != 0;
		int salt1 = h.getInt(16), salt2 = h.getInt(20);
		int[] sum = checksum(h, 0, 24, bigEndian, 0, 0);
		if (sum[0] != h.getInt(24) || sum[1] != h.getInt(28)) return;
		long size = w.size(), at = 32;
		ByteBuffer frame = ByteBuffer.allocate(24 + this.pageSize);
		Map<Integer, Long> pending = new HashMap<>();
		while (at + 24 + this.pageSize <= size) {
			readFully(w, frame, at);
			int pageNo = frame.getInt(0), commit = frame.getInt(4);
			if (frame.getInt(8) != salt1 || frame.getInt(12) != salt2 || pageNo <= 0) break;
			sum = checksum(frame, 0, 8, bigEndian, sum[0], sum[1]);
			sum = checksum(frame, 24, this.pageSize, bigEndian, sum[0], sum[1]);
			if (sum[0] != frame.getInt(16) || sum[1] != frame.getInt(20)) break;
			pending.put(pageNo, at + 24);
			if (commit != 0) {
				this.walPages.putAll(pending);
				pending.clear();
			}
			at += 24 + this.pageSize;
		}
	}

	/** SQLite's log checksum over b[from, from + len) (len a multiple of 8), from (s0, s1). */
	private static int[] checksum(ByteBuffer b, int from, int len, boolean bigEndian, int s0, int s1) {
		ByteOrder o = b.order();
		b.order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		for (int i = from; i < from + len; i += 8) {
			s0 += b.getInt(i) + s1;
			s1 += b.getInt(i + 4) + s0;
		}
		b.order(o);
		return new int[] {s0, s1};
	}

	/** Page n (1-based) into a buffer of its own (the log's copy when it has one). */
	private ByteBuffer page(int n) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(this.pageSize);
		Long w = this.walPages.get(n);
		if (w != null && this.wal != null) readFully(this.wal, b, w);
		else readFully(this.db, b, (long) (n - 1) * this.pageSize);
		return b;
	}

	/** A table's root page and its columns' names in order (from its CREATE TABLE statement), or null when there's no such table. */
	record Table(int root, List<String> columns) {
		int column(String name) {
			for (int i = 0; i < this.columns.size(); i++) if (this.columns.get(i).equalsIgnoreCase(name)) return i;
			return -1;
		}
	}

	@Nullable Table table(String name) throws IOException {
		Table[] found = new Table[1];
		this.rows(1, row -> {
			// sqlite_schema: type, name, tbl_name, rootpage, sql
			if (row.length >= 5 && "table".equals(row[0]) && name.equalsIgnoreCase(String.valueOf(row[1])) && row[3] instanceof Long root && row[4] instanceof String sql) {
				found[0] = new Table((int) (long) root, columns(sql));
				return false;
			}
			return true;
		});
		return found[0];
	}

	/** The column names of a CREATE TABLE statement, in order (constraints left out). */
	static List<String> columns(String sql) {
		int open = sql.indexOf('('), close = sql.lastIndexOf(')');
		List<String> out = new ArrayList<>();
		if (open < 0 || close < open) return out;
		String body = sql.substring(open + 1, close).replaceAll("--[^\n]*", " ");
		int depth = 0, start = 0;
		for (int i = 0; i <= body.length(); i++) {
			char c = i < body.length() ? body.charAt(i) : ',';  // (the end closes the last column as a comma would)
			if (c == '(') depth++;
			else if (c == ')') depth--;
			else if (c == ',' && (depth == 0 || i == body.length())) {
				String t = body.substring(start, i).strip(), first = t.split("\\s+")[0];
				start = i + 1;
				if (t.isEmpty() || first.toUpperCase(Locale.ROOT).matches("PRIMARY|UNIQUE|CHECK|FOREIGN|CONSTRAINT")) continue;
				out.add(first.replaceAll("^[\"`\\[]|[\"`\\]]$", ""));
			}
		}
		return out;
	}

	interface RowVisitor {
		/** A row's values (Long, Double, String, byte[] or null), in column order; false stops the walk. */
		boolean row(Object[] values) throws IOException;
	}

	/** Every row of the table rooted at `root`, in rowid order. False when the visitor stopped it. */
	boolean rows(int root, RowVisitor v) throws IOException {
		return this.walk(root, v, 0);
	}

	private boolean walk(int pageNo, RowVisitor v, int depth) throws IOException {
		if (depth > 64) throw new IOException("b-tree too deep");
		ByteBuffer p = this.page(pageNo);
		int h = pageNo == 1 ? 100 : 0, type = p.get(h) & 0xFF, cells = p.getShort(h + 3) & 0xFFFF;
		if (type == 5) {
			// interior table page: child pointers, then the rightmost
			for (int i = 0; i < cells; i++) if (!this.walk(p.getInt(p.getShort(h + 12 + 2 * i) & 0xFFFF), v, depth + 1)) return false;
			return this.walk(p.getInt(h + 8), v, depth + 1);
		}
		if (type != 13) throw new IOException("page " + pageNo + " isn't a table page (" + type + ")");
		long[] pos = new long[1];
		for (int i = 0; i < cells; i++) {
			pos[0] = p.getShort(h + 8 + 2 * i) & 0xFFFF;
			long payload = varint(p, pos);
			varint(p, pos);
			// (an INTEGER PRIMARY KEY column reads as null: its value is the rowid, which no caller here needs)
			if (!v.row(this.record(this.payload(p, (int) pos[0], payload)))) return false;
		}
		return true;
	}

	/** A leaf cell's payload of `size` bytes starting at `at`, its overflow pages followed. */
	private byte[] payload(ByteBuffer p, int at, long size) throws IOException {
		if (size > Integer.MAX_VALUE - 16) throw new IOException("payload too large");
		int n = (int) size;
		byte[] out = new byte[n];
		int x = this.usable - 35, m = ((this.usable - 12) * 32 / 255) - 23, k = m + ((n - m) % (this.usable - 4));
		int local = n <= x ? n : k <= x ? k : m;
		p.get(at, out, 0, local);
		if (n <= x) return out;
		int next = p.getInt(at + local), done = local, guard = 0;
		while (done < n) {
			if (next <= 0 || ++guard > 1 << 22) throw new IOException("broken overflow chain");
			ByteBuffer o = this.page(next);
			int len = Math.min(n - done, this.usable - 4);
			o.get(4, out, done, len);
			done += len;
			next = o.getInt(0);
		}
		return out;
	}

	private Object[] record(byte[] b) {
		ByteBuffer r = ByteBuffer.wrap(b);
		long[] pos = {0};
		int headerSize = (int) varint(r, pos), at = headerSize;
		List<Long> types = new ArrayList<>();
		while (pos[0] < headerSize) types.add(varint(r, pos));
		Object[] out = new Object[types.size()];
		for (int i = 0; i < out.length; i++) {
			long t = types.get(i);
			if (t >= 1 && t <= 6) {
				int len = t == 5 ? 6 : t == 6 ? 8 : (int) t, shift = 64 - len * 8;
				long v = 0;
				for (int k = 0; k < len; k++) v = v << 8 | (b[at + k] & 0xFF);
				out[i] = v << shift >> shift;  // (sign-extended from len bytes)
				at += len;
			} else if (t == 7) {
				out[i] = r.getDouble(at);
				at += 8;
			} else if (t == 8 || t == 9) {
				out[i] = t == 8 ? 0L : 1L;
			} else if (t >= 12) {
				int len = (int) ((t - (t % 2 == 0 ? 12 : 13)) / 2);
				if (t % 2 == 0) {
					byte[] blob = new byte[len];
					System.arraycopy(b, at, blob, 0, len);
					out[i] = blob;
				} else {
					out[i] = new String(b, at, len, this.text);
				}
				at += len;
			}
		}
		return out;
	}

	/** SQLite's varint at pos (advanced past it): 1-9 bytes, big-endian, 7 bits a byte, the ninth all 8. */
	private static long varint(ByteBuffer b, long[] pos) {
		long v = 0;
		int at = (int) pos[0];
		for (int i = 0; i < 9; i++) {
			int c = b.get(at++) & 0xFF;
			v = i == 8 ? v << 8 | c : v << 7 | (c & 0x7F);
			if ((c & 0x80) == 0) break;
		}
		pos[0] = at;
		return v;
	}
}
