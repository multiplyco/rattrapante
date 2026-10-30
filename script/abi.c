/* ABI assumptions used by the generated FFM bindings, checked against the
 * header emitted by the exact source build. */
#define USIZE_USIZE_USIZE_ (sizeof(size_t) * 3)
#include <automerge.h>
_Static_assert(sizeof(void*) == 8, "64-bit pointers required");
_Static_assert(sizeof(size_t) == 8, "64-bit size_t required");
_Static_assert(sizeof(bool) == 1, "C bool must match JAVA_BOOLEAN");
_Static_assert(sizeof(AMbyteSpan) == 16, "AMbyteSpan size changed");
_Static_assert(_Alignof(AMbyteSpan) == 8, "AMbyteSpan alignment changed");
_Static_assert(offsetof(AMbyteSpan, src) == 0, "AMbyteSpan.src moved");
_Static_assert(offsetof(AMbyteSpan, count) == 8, "AMbyteSpan.count moved");
_Static_assert(sizeof(AMitems) == 24, "AMitems size changed");
_Static_assert(_Alignof(AMitems) == 1, "AMitems alignment changed");
_Static_assert(sizeof(AMstatus) == 4, "C enum must match JAVA_INT");
_Static_assert(sizeof(AMobjType) == 4, "C enum must match JAVA_INT");
_Static_assert(sizeof(AMvalType) == 4, "C enum must match JAVA_INT");
