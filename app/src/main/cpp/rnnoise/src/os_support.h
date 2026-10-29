/* os_support.h — compatibility shim for the vendored RNNoise build.
 *
 * RNNoise's vec.h / vec_neon.h include "os_support.h", a header from
 * the Opus project that RNNoise's upstream build expects to be present
 * but does not ship in the rnnoise repository itself. The only macro
 * the vendored RNNoise sources actually use from it is OPUS_CLEAR
 * (vec.h, vec_neon.h); OPUS_COPY / OPUS_MOVE are provided as well so
 * the shim matches the upstream Opus semantics exactly:
 *
 *   OPUS_CLEAR(dst, n)  -> memset dst to zero, n elements
 *   OPUS_COPY(dst, src, n)  -> memcpy n elements
 *   OPUS_MOVE(dst, src, n)  -> memmove n elements
 *
 * (Element size is taken from the destination pointer, as in Opus.)
 */
#ifndef OS_SUPPORT_H
#define OS_SUPPORT_H

#include <string.h>

#ifndef OPUS_CLEAR
#define OPUS_CLEAR(dst, count) (memset((dst), 0, (count) * sizeof(*(dst))))
#endif

#ifndef OPUS_COPY
#define OPUS_COPY(dst, src, count) (memcpy((dst), (src), (count) * sizeof(*(dst))))
#endif

#ifndef OPUS_MOVE
#define OPUS_MOVE(dst, src, count) (memmove((dst), (src), (count) * sizeof(*(dst))))
#endif

#endif /* OS_SUPPORT_H */
