#include "DroneCoreDemoActor.h"
#include "DroneCoreComponent.h"
#include "Components/StaticMeshComponent.h"
#include "Engine/Engine.h"
#include "Engine/StaticMesh.h"
#include "HAL/PlatformTime.h"
#include "UObject/ConstructorHelpers.h"

ADroneCoreDemoActor::ADroneCoreDemoActor()
{
    PrimaryActorTick.bCanEverTick = true;
    PrimaryActorTick.TickGroup = TG_PrePhysics;
    Mesh = CreateDefaultSubobject<UStaticMeshComponent>(TEXT("DemoMesh"));
    SetRootComponent(Mesh);
    static ConstructorHelpers::FObjectFinder<UStaticMesh> Cube(TEXT("/Engine/BasicShapes/Cube.Cube"));
    if (Cube.Succeeded()) Mesh->SetStaticMesh(Cube.Object);
    Mesh->SetRelativeScale3D(FVector(0.25));
    Mesh->SetMobility(EComponentMobility::Movable);
    Mesh->SetSimulatePhysics(false);
    Core = CreateDefaultSubobject<UDroneCoreComponent>(TEXT("DroneCore"));
}
void ADroneCoreDemoActor::BeginPlay()
{
    Super::BeginPlay();
    // 【执行顺序】本Actor先提交测试输入，随后组件Tick推进模型，避免额外延迟一帧。
    Core->AddTickPrerequisiteActor(this);
    StartTimeSeconds = FPlatformTime::Seconds();
    if (bEnableOfflineDemo)
        UE_LOG(LogTemp, Warning, TEXT("DroneCoreBridge: OFFLINE DEMO input source enabled; no APP/service authorization is being tested."));
}
void ADroneCoreDemoActor::Tick(float DeltaSeconds)
{
    Super::Tick(DeltaSeconds);
    if (!Core) return;
    const double Now = FPlatformTime::Seconds();
    const double Elapsed = Now - StartTimeSeconds;
    if (bEnableOfflineDemo && Core->IsSimulationEnabled())
    {
        // 【离线测试来源】必须显式启用；不是正式APP/服务的授权输入通路。
        // 墙钟0~2s上升、2~4s停止、4~5s下降；此后不续期，观察超时保护。
        if (Elapsed >= 2.0 && !bStopSent) { Core->StopMotion(); bStopSent = true; }
        const bool bAscend = Elapsed < 2.0;
        const bool bDescend = Elapsed >= 4.0 && Elapsed < 5.0;
        if ((bAscend || bDescend) && (LastInputTimeSeconds < 0.0 || Now - LastInputTimeSeconds >= 0.1))
        {
            // 每约0.1s发送一次，TTL为0.5s；长帧/断点可能使保护提前触发并锁存。
            Core->SetVerticalInput(bAscend ? 0.3 : -0.3, 0.5);
            LastInputTimeSeconds = Now;
        }
    }
    if (bShowStatus && GEngine)
    {
        const auto& View = Core->Snapshot;
        const FString Text = FString::Printf(TEXT("OFFLINE vertical core | t=%.2f z=%.3fm vz=%.3fm/s mode=%d reason=%d | %s"),
            View.SimulationTimeSeconds, View.HeightMeters, View.VerticalSpeedMetersPerSecond,
            View.Mode, View.ProtectionReason, *Core->LastError);
        GEngine->AddOnScreenDebugMessage(static_cast<uint64>(GetUniqueID()), 0.1f,
            View.bValid ? FColor::Green : FColor::Red, Text);
    }
}
