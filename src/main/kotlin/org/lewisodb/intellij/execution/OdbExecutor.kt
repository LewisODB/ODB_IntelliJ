package org.lewisodb.intellij.execution

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

class OdbExecutor : Executor() {
    private val runExecutor: Executor
        get() = DefaultRunExecutor.getRunExecutorInstance()

    override fun getToolWindowId(): String = ToolWindowId.RUN
    override fun getToolWindowIcon(): Icon = runExecutor.toolWindowIcon
    override fun getIcon(): Icon = runExecutor.icon
    override fun getDisabledIcon(): Icon = runExecutor.disabledIcon
    override fun getDescription(): String = "Run the selected Java Application with Lewis ODB"
    override fun getActionName(): String = "Run with ODB"
    override fun getId(): String = ID
    override fun getStartActionText(): String = "Run with ODB"
    override fun getContextActionId(): String = "RunWithOdbContext"
    override fun getHelpId(): String? = null
    override fun isSupportedOnTarget(): Boolean = false

    companion object {
        const val ID = "RunWithOdb"
    }
}
