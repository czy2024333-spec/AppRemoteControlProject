using UnrealBuildTool;
using System.IO;

public class DroneCoreBridge : ModuleRules
{
    public DroneCoreBridge(ReadOnlyTargetRules Target) : base(Target)
    {
        PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;
        PublicDependencyModuleNames.AddRange(new[] { "Core", "CoreUObject", "Engine" });
        PrivateDependencyModuleNames.Add("Projects");
        PublicIncludePaths.Add(Path.Combine(PluginDirectory, "ThirdParty", "Include"));
        if (Target.Platform != UnrealTargetPlatform.Win64)
            throw new BuildException("DroneCoreBridge v0.1 only supports Win64.");
        string CoreDll = Path.Combine(PluginDirectory, "ThirdParty", "Win64", "drone_core_c.dll");
        if (!File.Exists(CoreDll))
            throw new BuildException("Missing core DLL. Run ue/prepare_ue_plugin.ps1 before copying the plugin.");
        RuntimeDependencies.Add(CoreDll, StagedFileType.NonUFS);
    }
}
