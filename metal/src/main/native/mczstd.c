// Zstandard decompression for the imports (LodZstd): the reference decoder's single-file sources (v1.5.6, BSD, fetched by build.gradle).
#include "zstddeclib-in.c"

// The decompressed size src's first frame names: >= 0; -1 when the frame doesn't say; -2 when src isn't a zstd frame.
long long mcz_content_size(const void *src, long long len) {
	unsigned long long n = ZSTD_getFrameContentSize(src, (size_t) len);
	return n == ZSTD_CONTENTSIZE_UNKNOWN ? -1 : n == ZSTD_CONTENTSIZE_ERROR || n > (1ull << 62) ? -2 : (long long) n;
}

// Every frame of src into dst: the bytes written; -2 when dst is too small; -1 on any other error.
long long mcz_decompress(void *dst, long long cap, const void *src, long long len) {
	size_t r = ZSTD_decompress(dst, (size_t) cap, src, (size_t) len);
	return ZSTD_isError(r) ? (ZSTD_getErrorCode(r) == ZSTD_error_dstSize_tooSmall ? -2 : -1) : (long long) r;
}
