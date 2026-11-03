using UnrealBuildTool;
using System.Collections.Generic;

public class DroneCoreSandboxTarget : TargetRules
{
    public DroneCoreSandboxTarget(TargetInfo Target) : base(Target)
    {
        Type = TargetType.Game;
        DefaultBuildSettings = BuildSettingsVersion.V2;
        ExtraModuleNames.Add("DroneCoreSandbox");
    }
}
