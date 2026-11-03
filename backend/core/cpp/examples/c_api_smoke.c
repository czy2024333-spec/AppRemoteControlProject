#include "drone_core_c.h"
#include <stdio.h>

int main(void) {
    dc_handle* handle = NULL;
    dc_state state;
    if (dc_create(NULL, &handle) != DC_OK) return 1;
    if (dc_set_control(handle, 0.3, 0.1, 100.0) != DC_OK || dc_step(handle, 105.0) != DC_OK ||
        dc_get_state(handle, &state) != DC_OK || state.mode != DC_PROTECTION || state.reason != DC_INPUT_TIMEOUT) {
        dc_destroy(handle);
        return 1;
    }
    dc_destroy(handle);
    puts("PASS native C consumer linked to x64 DLL");
    return 0;
}
