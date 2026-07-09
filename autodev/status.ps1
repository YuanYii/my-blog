# autodev-flow 工作流状态面板（Windows PowerShell）
# 用法:
#   .\status.ps1                     # 展示阶段状态
#   .\status.ps1 -Watch             # 动态刷新（默认 10 秒）
#   .\status.ps1 -Watch -Interval 5 # 自定义刷新间隔（秒）
#   .\status.ps1 -Tasks             # 展示任务状态
#   .\status.ps1 -Watch -Tasks      # 动态刷新 + 任务状态

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$Watch = $false
$Tasks = $false
$Interval = 10

foreach ($arg in $args) {
    switch ($arg) {
        "-Watch"   { $Watch = $true }
        "-w"       { $Watch = $true }
        "-Tasks"   { $Tasks = $true }
        "-t"       { $Tasks = $true }
        default {
            if ($Watch -and $arg -match '^\d+$') {
                $Interval = [int]$arg
            }
        }
    }
}

$ErrorActionPreference = "Stop"
$STATUS_FILE = "autodev\status.json"

# 检查 status.json
if (-not (Test-Path $STATUS_FILE)) {
    Write-Host "❌ 状态文件不存在: $STATUS_FILE" -ForegroundColor Red
    exit 1
}

# 读取 status.json
function Get-Status {
    $raw = Get-Content $STATUS_FILE -Raw -Encoding UTF8
    return ($raw | ConvertFrom-Json)
}

# 从 status.json 读取 RUN 值
$script:RUN = (Get-Status).RUN

# 状态图标映射
function Get-StatusIcon($status) {
    switch ($status) {
        "success"  { return "✅" }
        "failed"   { return "❌" }
        "skipped"  { return "⏭️ " }
        "pending"  { return "⏸️ " }
        "running"  { return "🟠" }
        default    { return "❓" }
    }
}

# 阶段名称映射
function Get-StageName($stage) {
    switch ($stage) {
        "Stage_1" { return "需求拟定" }
        "Stage_2" { return "代码实现" }
        "Stage_3" { return "代码审查" }
        "Stage_4" { return "静态审计" }
        "Stage_5" { return "集成测试" }
        "Stage_6" { return "门控复核" }
        "Stage_7" { return "文档同步" }
        "Stage_8" { return "发布" }
        default   { return "未知阶段" }
    }
}

# 进度条
function Get-ProgressBar($current, $total) {
    $width = 20
    $filled = [math]::Floor($current * $width / $total)
    $empty = $width - $filled
    $bar = "█" * $filled + "░" * $empty
    return "[$bar]"
}

# 获取任务文档路径
function Get-TaskFile {
    # 优先用 RUN 值
    if ($script:RUN) {
        $file = "autodev\auto_iteration\$($script:RUN).md"
        if (Test-Path $file) { return $file }
    }
    # fallback: 当前日期
    $today = Get-Date -Format "yyyyMMdd"
    $file = "autodev\auto_iteration\$today.md"
    if (Test-Path $file) { return $file }
    # 查找最近 7 天
    for ($d = 1; $d -le 7; $d++) {
        $past = (Get-Date).AddDays(-$d).ToString("yyyyMMdd")
        $file = "autodev\auto_iteration\$past.md"
        if (Test-Path $file) { return $file }
    }
    return $null
}

# 渲染任务状态
function Show-Tasks {
    $taskFile = Get-TaskFile
    if (-not $taskFile) {
        Write-Host "  📋 未找到任务文档"
        return
    }

    $runLabel = [System.IO.Path]::GetFileNameWithoutExtension($taskFile)

    Write-Host ""
    Write-Host "──────────────────────────────────────────────────────────────"
    Write-Host "  📋 任务状态 · $runLabel"
    Write-Host "──────────────────────────────────────────────────────────────"

    $lines = Get-Content $taskFile -Encoding UTF8

    $pendingTasks = @()
    $doingTasks = @()
    $doneTasks = @()
    $failedTasks = @()

    foreach ($line in $lines) {
        # 匹配任务行
        if ($line -match "^### (\d{8}-(BUG|OPT|DEV)-\d+)") {
            $taskId = $Matches[1]
            if ($line -match "✅") {
                $doneTasks += "    ✅ $taskId"
            } elseif ($line -match "❌") {
                $failedTasks += "    ❌ $taskId"
            } elseif ($line -match "🟠") {
                $doingTasks += "    🔧 $taskId"
            } else {
                $pendingTasks += "    ⏳ $taskId"
            }
        }
        # 匹配 autopush 卡片
        elseif ($line -match "^### (autopush-\d{8}-(BUG|OPT|DEV)-\d+)") {
            $pendingTasks += "    🔄 $($Matches[1])"
        }
    }

    $pendingCount = $pendingTasks.Count + $doingTasks.Count
    $doneCount = $doneTasks.Count
    $failedCount = $failedTasks.Count

    if ($pendingCount -gt 0) {
        Write-Host ""
        Write-Host "    🔴 待处理 ($pendingCount)" -ForegroundColor Yellow
        $doingTasks + $pendingTasks | ForEach-Object { Write-Host $_ }
    }

    if ($doneCount -gt 0) {
        Write-Host "    ✅ 已完成 ($doneCount)" -ForegroundColor Green
        $doneTasks | ForEach-Object { Write-Host $_ }
    }

    if ($failedCount -gt 0) {
        Write-Host "    ❌ 失败 ($failedCount)" -ForegroundColor Red
        $failedTasks | ForEach-Object { Write-Host $_ }
    }

    Write-Host "    ─────────────────────────────────────"
    Write-Host "    📊 待处理: $pendingCount | 已完成: $doneCount | 失败: $failedCount"
}

# 渲染面板
function Show-Panel {
    $status = Get-Status
    $stages = $status.stages

    # 动态检测最大 Stage 编号
    $maxStage = 7
    $stages.PSObject.Properties | ForEach-Object {
        $num = [int]($_.Name -replace "Stage_", "")
        if ($num -gt $maxStage) { $maxStage = $num }
    }

    $totalCount = 0
    $doneCount = 0
    $failCount = 0
    $skipCount = 0

    for ($i = 1; $i -le $maxStage; $i++) {
        $stageKey = "Stage_$i"
        $stageData = $stages.$stageKey
        $st = if ($stageData) { $stageData.status } else { "pending" }
        $totalCount++
        switch ($st) {
            "success" { $doneCount++ }
            "failed"  { $failCount++ }
            "skipped" { $skipCount++ }
        }
    }

    $activeCount = $totalCount - $skipCount

    # 清屏（watch 模式）
    if ($Watch) { Clear-Host }

    $runLabel = if ($script:RUN) { $script:RUN } else { Get-Date -Format "yyyyMMdd" }

    Write-Host ""
    Write-Host "╔══════════════════════════════════════════════════════════════╗"
    Write-Host "║              AutoDev Flow 工作流状态 · $runLabel              ║"
    Write-Host "╚══════════════════════════════════════════════════════════════╝"
    Write-Host ""
    Write-Host "  📊 总览: $activeCount 个有效阶段 | ✅ $doneCount 完成 | ❌ $failCount 失败 | ⏭️  $skipCount 跳过"

    if ($activeCount -gt 0) {
        $pct = [math]::Floor($doneCount * 100 / $activeCount)
        Write-Host "  $(Get-ProgressBar $doneCount $activeCount) $pct%"
    } else {
        Write-Host "  [░░░░░░░░░░░░░░░░░░░] 0%"
    }

    Write-Host ""
    Write-Host "──────────────────────────────────────────────────────────────"

    for ($i = 1; $i -le $maxStage; $i++) {
        $stageKey = "Stage_$i"
        $stageData = $stages.$stageKey
        $name = Get-StageName $stageKey
        $st = if ($stageData) { $stageData.status } else { "pending" }
        $lastUpdate = if ($stageData -and $stageData.last_update) { $stageData.last_update } else { $null }
        $icon = Get-StatusIcon $st

        $line = "  $icon Stage $i $($name.PadRight(8)) │ $st"
        if ($lastUpdate) {
            $line += "  📅 $lastUpdate"
        }
        Write-Host $line
    }

    Write-Host ""
    Write-Host "──────────────────────────────────────────────────────────────"
    Write-Host "  📁 来源: $STATUS_FILE"
    if ($Watch) {
        Write-Host "  🔄 刷新中... 每 $Interval 秒 (Ctrl+C 退出)"
    }
    Write-Host ""

    if ($Tasks) { Show-Tasks }
    Write-Host ""
}

# 主循环
if ($Watch) {
    while ($true) {
        Show-Panel
        Start-Sleep -Seconds $Interval
    }
} else {
    Show-Panel
}
