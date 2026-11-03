#pragma once
#include "CoreMinimal.h"
#include "Components/ActorComponent.h"
#include "DroneCoreBridgeModule.h"
#include "frame_scheduler.hpp"
#include "DroneCoreComponent.generated.h"

USTRUCT(BlueprintType)
struct FDroneCoreView
{
    GENERATED_BODY()
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") bool bValid = false;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") double SimulationTimeSeconds = 0.0;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") double HeightMeters = 0.0;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") double VerticalSpeedMetersPerSecond = 0.0;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") double TargetHeightMeters = 0.0;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") int32 Mode = 0;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") int32 ProtectionReason = 0;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") int64 Sequence = 0;
};

UCLASS(ClassGroup=(Simulation), meta=(BlueprintSpawnableComponent))
class DRONECOREBRIDGE_API UDroneCoreComponent : public UActorComponent
{
    GENERATED_BODY()
public:
    UDroneCoreComponent();
    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category="Drone Core") bool bAutoInitialize = true;
    UPROPERTY(EditAnywhere, BlueprintReadOnly, Category="Drone Core", meta=(ClampMin="0.3", ClampMax="10.0")) double InitialHeightMeters = 1.0;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") FDroneCoreView Snapshot;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") FString LastError;
    UPROPERTY(BlueprintReadOnly, Category="Drone Core") double DroppedSimulationSeconds = 0.0;

    UFUNCTION(BlueprintCallable, Category="Drone Core") bool InitializeSimulation();
    // Trusted B/C entry point. No keyboard, video or network control route here.
    UFUNCTION(BlueprintCallable, Category="Drone Core") bool SetVerticalInput(double SpeedMetersPerSecond, double RemainingTTLSeconds);
    UFUNCTION(BlueprintCallable, Category="Drone Core") bool StopMotion();
    UFUNCTION(BlueprintCallable, Category="Drone Core") bool ProtectMotion();
    UFUNCTION(BlueprintCallable, Category="Drone Core") bool ResumeAfterNeutral(bool bNeutralConfirmed);
    UFUNCTION(BlueprintCallable, Category="Drone Core") void DisableSimulation();
    UFUNCTION(BlueprintPure, Category="Drone Core") bool IsSimulationEnabled() const { return Handle && Scheduler.Enabled(); }

    virtual void TickComponent(float DeltaTime, ELevelTick TickType, FActorComponentTickFunction* ThisTickFunction) override;
protected:
    virtual void BeginPlay() override;
    virtual void EndPlay(const EEndPlayReason::Type EndPlayReason) override;
private:
    bool UpdateSnapshot(bool bApplyPosition);
    bool CheckStatus(int32 Status, const TCHAR* Operation);
    void ReleaseCore();
    dc_handle* Handle = nullptr;
    FDroneCoreApi Api;
    drone::FrameScheduler Scheduler;
    FVector WorldOriginCentimeters = FVector::ZeroVector;
};
