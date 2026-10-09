package mcopt.metal.lod;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Zstandard decompression (native/mczstd.c: the reference decoder in the native library), for Distant Horizons' saved
 * terrain (LodDhImport), which it compresses with zstd by default. Any thread.
 */
final class LodZstd {
	private final MethodHandle size, decompress;

	LodZstd(SymbolLookup lib) {
		Linker linker = Linker.nativeLinker();
		this.size = linker.downcallHandle(lib.find("mcz_content_size").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG));
		this.decompress = linker.downcallHandle(lib.find("mcz_decompress").orElseThrow(),
			FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));
	}

	private static volatile LodZstd instance;

	/** The native library's (mcopt.metal.MetalBridge.library()). */
	static LodZstd get() {
		LodZstd z = instance;
		if (z == null) instance = z = new LodZstd(mcopt.metal.MetalBridge.library());
		return z;
	}

	/** src's zstd frames, decompressed (at most max bytes). */
	byte[] decompress(byte[] src, int max) throws IOException {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment in = a.allocate(Math.max(1, src.length));
			MemorySegment.copy(src, 0, in, JAVA_BYTE, 0, src.length);
			long named = (long) this.size.invokeExact(in.address(), (long) src.length);
			if (named == -2) throw new IOException("not zstd data");
			if (named > max) throw new IOException("zstd data too large: " + named);
			// (the first frame's size, or room for 8 x the input when it doesn't say; more while that isn't enough: more frames)
			long cap = named >= 0 ? Math.max(named, 1) : Math.min(max, Math.max(1L << 16, src.length * 8L));
			while (true) {
				MemorySegment out = a.allocate(cap);
				long n = (long) this.decompress.invokeExact(out.address(), cap, in.address(), (long) src.length);
				if (n >= 0) return out.asSlice(0, n).toArray(JAVA_BYTE);
				if (n == -1 || cap >= max) throw new IOException("bad zstd data");
				cap = Math.min(max, cap * 2);
			}
		} catch (IOException | RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IOException(t);
		}
	}
}
