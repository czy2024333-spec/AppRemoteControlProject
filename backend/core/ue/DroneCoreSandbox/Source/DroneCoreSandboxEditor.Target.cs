using UnrealBuildTool;
using System.Collections.Generic;

public class DroneCoreSandboxEditorTarget : TargetRules
{
    public DroneCoreSandboxEditorTarget(TargetInfo Target) : base(Target)
    {
        Type = TargetType.Editor;
        DefaultBuildSettings = BuildSettingsVersion.V2;
        ExtraModuleNames.Add("DroneCoreSandbox");
    }
}
