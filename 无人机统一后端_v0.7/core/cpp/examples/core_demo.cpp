#include "drone_core.hpp"
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>

int main(int argc, char** argv) {
    try {
        const char* path = argc > 1 ? argv[1] : "core_demo.csv";
        std::ofstream out(path);
        if (!out) throw std::runtime_error("cannot open demo CSV");
        out << "time_s,clock_s,sequence,height_m,vz_m_s,thrust_n,target_m,mode,reason\n" << std::setprecision(17);
        drone::Core core;
        bool ascended = false, stopped = false, descended = false;
        double held_altitude = 0.0;
        const auto write = [&] {
            const auto s = core.State();
            out << s.simulation_time_s << ',' << s.monotonic_time_s << ',' << s.sequence << ','
                << s.plant.altitude_m << ',' << s.plant.vertical_speed_m_s << ',' << s.plant.thrust_n << ','
                << s.target_altitude_m << ',' << static_cast<int>(s.mode) << ',' << static_cast<int>(s.reason) << '\n';
        };
        write();
        for (int tick = 0; tick < 800; ++tick) {
            const double now = tick * 0.01;
            if (tick < 200 && tick % 10 == 0) core.SetControl(0.3, 0.5, now);
            else if (tick == 200) { ascended = core.State().plant.altitude_m > 1.3; core.Stop(); }
            else if (tick == 400) {
                stopped = std::abs(core.State().plant.vertical_speed_m_s) < 0.01;
                held_altitude = core.State().plant.altitude_m;
                core.SetControl(-0.3, 0.5, now);
            }
            else if (tick > 400 && tick < 500 && tick % 10 == 0) core.SetControl(-0.3, 0.5, now);
            else if (tick == 500) descended = core.State().plant.altitude_m < held_altitude;
            core.Step((tick + 1) * 0.01);
            write();
        }
        const auto s = core.State();
        const bool protected_hover = s.mode == drone::Mode::Protected && s.reason == drone::Reason::InputTimeout
            && std::abs(s.plant.vertical_speed_m_s) < 0.01;
        if (!ascended || !stopped || !descended || !protected_hover) throw std::runtime_error("fixed input demo failed");
        out.flush();
        if (!out) throw std::runtime_error("failed to write CSV");
        std::cout << "PASS demo: ascend/stop/descend/timeout/protected_hover, final_z=" << s.plant.altitude_m << '\n';
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
