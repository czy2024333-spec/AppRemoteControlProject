#include "DroneCoreComponent.h"
#include "Components/PrimitiveComponent.h"
#include "Components/SceneComponent.h"
#include "GameFramework/Actor.h"
#include "GameFramework/MovementComponent.h"
#include "HAL/PlatformTime.h"

UDroneCoreComponent::UDroneCoreComponent()
{
    PrimaryComponentTick.bCanEverTick = true;
    PrimaryComponentTick.TickGroup = TG_PrePhysics;
}
void UDroneCoreComponent::BeginPlay()
{
    Super::BeginPlay();
    if (bAutoInitialize) InitializeSimulation();
}
bool UDroneCoreComponent::CheckStatus(int32 Status, const TCHAR* Operation)
{
    if (Status == DC_OK) { LastError.Empty(); return true; }
    LastError = FString::Printf(TEXT("%s failed, C status=%d"), Operation, Status);
    return false;
}
bool UDroneCoreComponent::InitializeSimulation()
{
    // 【线程边界】Actor/UObject操作在游戏线程执行；网络线程先排队，不能直接调用这里。
    if (!IsInGameThread()) return false;
    if (Handle) { LastError = TEXT("Already initialized; use ResumeAfterNeutral for a stopped instance."); return false; }
    AActor* Owner = GetOwner();
    if (!Owner || !Owner->GetRootComponent()) { LastError = TEXT("An Actor with a root component is required."); return false; }
    // 【唯一运动来源】拒绝网络位置复制和活动MovementComponent，防止重复驱动同一Actor。
    if (Owner->GetReplicateMovement()) { LastError = TEXT("Disable replicated movement: the core must own this Actor state."); return false; }
    TArray<UMovementComponent*> Movement;
    Owner->GetComponents<UMovementComponent>(Movement);
    for (UMovementComponent* Item : Movement)
        if (Item && Item->IsActive()) { LastError = TEXT("An active UE MovementComponent would drive the same state."); return false; }
    FDroneCoreBridgeModule* Module = FModuleManager::GetModulePtr<FDroneCoreBridgeModule>(TEXT("DroneCoreBridge"));
    if (!Module || !Module->IsReady()) { LastError = TEXT("Core DLL is unavailable. Check the Output Log."); return false; }
    Api = Module->Api();
    dc_config Config{};
    if (!CheckStatus(Api.DefaultConfig(&Config), TEXT("DefaultConfig"))) return false;
    Config.initial_altitude_m = InitialHeightMeters;
    // 【每设备独立状态】组件持有自己的核心句柄，多个Actor不共享位置、控制器或TTL。
    if (!CheckStatus(Api.Create(&Config, &Handle), TEXT("Create"))) return false;
    dc_state State{};
    if (!CheckStatus(Api.GetState(Handle, &State), TEXT("InitialState")) || State.interface_version != 1)
    {
        LastError = TEXT("Core snapshot or interface version mismatch.");
        ReleaseCore();
        return false;
    }
    if (!Scheduler.Initialize(Config.step_s, FPlatformTime::Seconds()))
    {
        LastError = TEXT("Scheduler initialization failed."); ReleaseCore(); return false;
    }
    // 【原点对齐】扣掉核心初始高度，让初始化时的Actor位置保持不变；核心m转换成UE cm。
    WorldOriginCentimeters = Owner->GetActorLocation() - FVector(0.0, 0.0, InitialHeightMeters * 100.0);
    Owner->GetRootComponent()->SetMobility(EComponentMobility::Movable);
    TArray<UPrimitiveComponent*> Primitives;
    Owner->GetComponents<UPrimitiveComponent>(Primitives);
    // 【关闭重复积分】位置由核心计算，UE物理不能再对这个Actor计算一次重力运动。
    for (UPrimitiveComponent* Primitive : Primitives) if (Primitive) Primitive->SetSimulatePhysics(false);
    Snapshot.bValid = true;
    return UpdateSnapshot(true);
}
bool UDroneCoreComponent::SetVerticalInput(double Speed, double TTL)
{
    // 【B→A接口】Speed单位m/s，TTL为剩余秒数；这里只执行已通过B鉴权与仲裁的输入。
    if (!IsInGameThread() || !Handle || !Scheduler.Enabled()) return false;
    const bool bOK = CheckStatus(Api.SetControl(Handle, Speed, TTL, FPlatformTime::Seconds()), TEXT("SetControl"));
    UpdateSnapshot(false);
    return bOK;
}
bool UDroneCoreComponent::StopMotion()
{
    if (!IsInGameThread() || !Handle) return false;
    const bool bOK = CheckStatus(Api.Stop(Handle), TEXT("Stop"));
    UpdateSnapshot(false); return bOK;
}
bool UDroneCoreComponent::ProtectMotion()
{
    if (!IsInGameThread() || !Handle) return false;
    const bool bOK = CheckStatus(Api.Protect(Handle, DC_SERVICE_PROTECTION), TEXT("Protect"));
    UpdateSnapshot(false); return bOK;
}
void UDroneCoreComponent::DisableSimulation()
{
    // 【停用顺序】先锁存保护清掉旧输入，再停调度；本接口只冻结，不是回放播放器。
    if (!IsInGameThread()) return;
    ProtectMotion();
    Scheduler.Disable();
}
bool UDroneCoreComponent::ResumeAfterNeutral(bool bNeutralConfirmed)
{
    if (!IsInGameThread() || !Handle) return false;
    dc_state Current{};
    if (Api.GetState(Handle, &Current) != DC_OK) return false;
    // 【恢复前核对】外部移动过Actor就拒绝恢复，避免回放位置与核心内部真值不一致。
    const FVector Expected = WorldOriginCentimeters + FVector(Current.position_m[0] * 100.0, -Current.position_m[1] * 100.0, Current.position_m[2] * 100.0);
    if (!GetOwner() || !GetOwner()->GetActorLocation().Equals(Expected, 1.0))
    {
        LastError = TEXT("Actor pose changed outside the core; cannot resume from a replay/external pose.");
        return false;
    }
    if (!CheckStatus(Api.Resume(Handle, bNeutralConfirmed ? 1 : 0), TEXT("Resume"))) return false;
    dc_config Config{};
    if (!CheckStatus(Api.DefaultConfig(&Config), TEXT("DefaultConfig"))) return false;
    // Vertical v0.1 cannot synchronize an arbitrary replay pose back into its plant.
    // DisableSimulation is a freeze only; do not move the Actor while disabled.
    if (!Scheduler.Initialize(Config.step_s, FPlatformTime::Seconds())) return false;
    return UpdateSnapshot(false);
}
bool UDroneCoreComponent::UpdateSnapshot(bool bApplyPosition)
{
    dc_state State{};
    if (!Handle || Api.GetState(Handle, &State) != DC_OK) { Snapshot.bValid = false; return false; }
    Snapshot.bValid = true;
    Snapshot.SimulationTimeSeconds = State.simulation_time_s;
    Snapshot.HeightMeters = State.position_m[2];
    Snapshot.VerticalSpeedMetersPerSecond = State.velocity_m_s[2];
    Snapshot.TargetHeightMeters = State.target_altitude_m;
    Snapshot.Mode = State.mode;
    Snapshot.ProtectionReason = State.reason;
    Snapshot.Sequence = static_cast<int64>(State.sequence);
    if (bApplyPosition && GetOwner())
    {
        // 【显示输出】核心x前/y左/z上→UE x前/y右/z上，位置乘100且y反号。
        // 本版不做碰撞扫掠；不能用此位置更新代替避障或地面接触逻辑。
        const FVector Position = WorldOriginCentimeters + FVector(State.position_m[0] * 100.0, -State.position_m[1] * 100.0, State.position_m[2] * 100.0);
        // Vertical-only prototype preserves the mesh's visual rotation.
        GetOwner()->SetActorLocation(Position, false, nullptr, ETeleportType::TeleportPhysics);
    }
    return true;
}
void UDroneCoreComponent::TickComponent(float DeltaTime, ELevelTick TickType, FActorComponentTickFunction* ThisTickFunction)
{
    Super::TickComponent(DeltaTime, TickType, ThisTickFunction);
    if (!Handle || !Scheduler.Enabled()) return;
    // 【渲染帧→仿真步】DeltaTime进入累积器，内部按0.01s固定步长调用DLL。
    // 长帧先保护并丢积压；核心状态码非零时冻结，成功返回仍需读mode判断是否保护。
    const auto Stats = Scheduler.Advance(static_cast<double>(DeltaTime), FPlatformTime::Seconds(),
        [this](double Now) { return Api.Step(Handle, Now); },
        [this]() { return Api.Protect(Handle, DC_SERVICE_PROTECTION); });
    DroppedSimulationSeconds += Stats.dropped_time_s;
    if (Stats.result == drone::FrameResult::InvalidInput || Stats.result == drone::FrameResult::CoreError)
    {
        Api.Protect(Handle, DC_SERVICE_PROTECTION);
        Scheduler.Disable();
        LastError = TEXT("Scheduler input/core failure; simulation frozen.");
    }
    else if (Stats.result == drone::FrameResult::ProtectedGap)
    {
        UE_LOG(LogTemp, Warning, TEXT("DroneCoreBridge: frame gap protected; discarded %.3f simulation seconds"), Stats.dropped_time_s);
    }
    if (!UpdateSnapshot(true))
    {
        Api.Protect(Handle, DC_SERVICE_PROTECTION);
        Scheduler.Disable();
        LastError = TEXT("Snapshot failed; simulation frozen.");
    }
}
void UDroneCoreComponent::ReleaseCore()
{
    // 【生命周期】先阻止新的step，再由原DLL销毁句柄，最后清空指针避免重复使用。
    Scheduler.Disable();
    if (Handle && Api.Destroy) Api.Destroy(Handle);
    Handle = nullptr;
    Snapshot.bValid = false;
}
void UDroneCoreComponent::EndPlay(const EEndPlayReason::Type Reason)
{
    ReleaseCore();
    Super::EndPlay(Reason);
}
