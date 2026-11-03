#pragma once
#include "CoreMinimal.h"
#include "GameFramework/Actor.h"
#include "DroneCoreDemoActor.generated.h"

class UStaticMeshComponent;
class UDroneCoreComponent;

// Offline test source only. It is not a service/APP input adapter.
UCLASS()
class DRONECOREBRIDGE_API ADroneCoreDemoActor : public AActor
{
    GENERATED_BODY()
public:
    ADroneCoreDemoActor();
    UPROPERTY(VisibleAnywhere, BlueprintReadOnly, Category="Drone Core") TObjectPtr<UStaticMeshComponent> Mesh;
    UPROPERTY(VisibleAnywhere, BlueprintReadOnly, Category="Drone Core") TObjectPtr<UDroneCoreComponent> Core;
    UPROPERTY(EditAnywhere, BlueprintReadWrite, Category="Offline Demo") bool bEnableOfflineDemo = false;
    UPROPERTY(EditAnywhere, BlueprintReadWrite, Category="Offline Demo") bool bShowStatus = true;
    virtual void Tick(float DeltaSeconds) override;
protected:
    virtual void BeginPlay() override;
private:
    double StartTimeSeconds = 0.0;
    double LastInputTimeSeconds = -1.0;
    bool bStopSent = false;
};
