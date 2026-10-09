// Zstandard decompression for the far terrain's imports (LodZstd): the reference decoder (v1.5.6, BSD, fetched by
// build.gradle; its single-file decoder's sources) and two calls around it. Plain C.
#include "zstddeclib-in.c"

// The decompressed size src's first frame names: >= 0; -1 when the frame doesn't say; -2 when src isn't a zstd frame.
long long mcz_content_size(const void *src, long long len) {
	unsigned long long n = ZSTD_getFrameContentSize(src, (size_t) len);
	if (n == ZSTD_CONTENTSIZE_UNKNOWN) return -1;
	if (n == ZSTD_CONTENTSIZE_ERROR || n > (1ull << 62)) return -2;
	return (long long) n;
}

// Every frame of src into dst: the bytes written; -2 when dst is too small; -1 on any other error.
long long mcz_decompress(void *dst, long long cap, const void *src, long long len) {
	size_t r = ZSTD_decompress(dst, (size_t) cap, src, (size_t) len);
	if (ZSTD_isError(r)) return ZSTD_getErrorCode(r) == ZSTD_error_dstSize_tooSmall ? -2 : -1;
	return (long long) r;
}
