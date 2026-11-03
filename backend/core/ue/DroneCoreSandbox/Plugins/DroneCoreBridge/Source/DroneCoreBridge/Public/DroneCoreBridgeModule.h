#pragma once
#include "CoreMinimal.h"
#include "Modules/ModuleManager.h"
#include "drone_core_c.h"

struct FDroneCoreApi
{
    decltype(&dc_default_config) DefaultConfig = nullptr;
    decltype(&dc_create) Create = nullptr;
    decltype(&dc_destroy) Destroy = nullptr;
    decltype(&dc_reset) Reset = nullptr;
    decltype(&dc_set_control) SetControl = nullptr;
    decltype(&dc_step) Step = nullptr;
    decltype(&dc_stop) Stop = nullptr;
    decltype(&dc_protect) Protect = nullptr;
    decltype(&dc_resume) Resume = nullptr;
    decltype(&dc_get_state) GetState = nullptr;
};

class DRONECOREBRIDGE_API FDroneCoreBridgeModule : public IModuleInterface
{
public:
    virtual void StartupModule() override;
    virtual void ShutdownModule() override;
    const FDroneCoreApi& Api() const { return CoreApi; }
    bool IsReady() const { return bReady; }
private:
    FDroneCoreApi CoreApi;
    void* DllHandle = nullptr;
    bool bReady = false;
};
