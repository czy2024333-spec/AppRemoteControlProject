#include "DroneCoreBridgeModule.h"
#include "Interfaces/IPluginManager.h"
#include "HAL/PlatformProcess.h"
#include "Misc/Paths.h"

void FDroneCoreBridgeModule::StartupModule()
{
    // 【模块加载】从插件目录定位DLL，不依赖某台电脑上的绝对路径。
    const TSharedPtr<IPlugin> Plugin = IPluginManager::Get().FindPlugin(TEXT("DroneCoreBridge"));
    if (!Plugin.IsValid()) return;
    const FString Path = FPaths::Combine(Plugin->GetBaseDir(), TEXT("ThirdParty/Win64/drone_core_c.dll"));
    DllHandle = FPlatformProcess::GetDllHandle(*Path);
    if (!DllHandle)
    {
        UE_LOG(LogTemp, Error, TEXT("DroneCoreBridge: cannot load %s"), *Path);
        return;
    }
    // 【动态绑定】按导出名称取得函数指针；有任何入口缺失都不能启用组件。
#define LOAD_DC(Member, Name) CoreApi.Member = reinterpret_cast<decltype(CoreApi.Member)>(FPlatformProcess::GetDllExport(DllHandle, TEXT(Name)))
    LOAD_DC(DefaultConfig, "dc_default_config");
    LOAD_DC(Create, "dc_create");
    LOAD_DC(Destroy, "dc_destroy");
    LOAD_DC(Reset, "dc_reset");
    LOAD_DC(SetControl, "dc_set_control");
    LOAD_DC(Step, "dc_step");
    LOAD_DC(Stop, "dc_stop");
    LOAD_DC(Protect, "dc_protect");
    LOAD_DC(Resume, "dc_resume");
    LOAD_DC(GetState, "dc_get_state");
#undef LOAD_DC
    bReady = CoreApi.DefaultConfig && CoreApi.Create && CoreApi.Destroy && CoreApi.Reset && CoreApi.SetControl
        && CoreApi.Step && CoreApi.Stop && CoreApi.Protect && CoreApi.Resume && CoreApi.GetState;
    if (!bReady)
    {
        UE_LOG(LogTemp, Error, TEXT("DroneCoreBridge: incomplete C API"));
        ShutdownModule();
    }
}
void FDroneCoreBridgeModule::ShutdownModule()
{
    // 【卸载约定】组件应在EndPlay先释放核心句柄，再卸载DLL；当前未验证热重载。
    bReady = false;
    CoreApi = {};
    if (DllHandle) FPlatformProcess::FreeDllHandle(DllHandle);
    DllHandle = nullptr;
}
IMPLEMENT_MODULE(FDroneCoreBridgeModule, DroneCoreBridge)
