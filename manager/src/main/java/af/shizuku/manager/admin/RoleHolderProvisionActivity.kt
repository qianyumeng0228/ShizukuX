package af.shizuku.manager.admin

import android.app.Activity
import android.os.Bundle

/**
 * RoleHolder 资格占位组件。
 *
 * `RoleManager` 授予 `android.app.role.DEVICE_POLICY_MANAGEMENT` 时，会校验候选包是否声明了
 * 一个满足下列条件的组件：
 *
 * - intent-filter 含
 *   `android.app.action.ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE`
 * - 该组件受 `android.permission.LAUNCH_DEVICE_MANAGER_SETUP` 保护
 *
 * 缺任一条件时资格校验直接失败（logcat 表现为
 * `not qualified ... due to missing RequiredComponent{...}`），与
 * `cmd role set-bypassing-role-qualification` 无关。
 *
 * 本 Activity 只承担声明作用：受 signature 级权限保护，外部无法拉起；
 * 万一被系统内部拉起，也会立刻结束、不显示界面。
 */
class RoleHolderProvisionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
