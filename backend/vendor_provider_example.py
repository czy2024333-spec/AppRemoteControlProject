"""供应商适配契约占位：甲方提供硬件与SDK后填入实际调用，不生成模拟硬件数据。"""


def open_device(config):
    # 在此调用供应商SDK打开设备，检查型号、固件、SDK版本、许可证和通信条件。
    # 返回对象须实现read_event(timeout_s)、is_connected()、close()。
    raise NotImplementedError('硬件及SDK尚未提供，无法打开实际设备；请按SDK接入说明实现供应商适配模块')
