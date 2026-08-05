#!/usr/bin/env python3
"""
autodev-flow Graph Parser & Runtime Engine (v2.0)
负责工作流图模版的实例化、节点就绪检测、条件边评估与状态机持久化。
使用标准 Python3 运行（无需外部依赖，自动兼容 PyYAML 或内置 JSON/YAML 解析）。
"""

import sys
import os
import json
import re
import time
from datetime import datetime, timezone, timedelta

# 强行设置 UTC+8 / 时区
os.environ['TZ'] = 'Asia/Shanghai'
if hasattr(time, 'tzset'):
    time.tzset()

TZ_BEIJING = timezone(timedelta(hours=8))

def get_now_utc8():
    """获取当前 UTC+8 (Asia/Shanghai) 时间"""
    return datetime.now(TZ_BEIJING)

def load_yaml_or_json(file_path):
    """读取 YAML 或 JSON 文件 (自带极简 YAML 解析器兜底，避免缺失 PyYAML 依赖)"""
    if not os.path.exists(file_path):
        return None
    try:
        import yaml
        with open(file_path, 'r', encoding='utf-8') as f:
            return yaml.safe_load(f)
    except ImportError:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        try:
            return json.loads(content)
        except Exception:
            pass
        try:
            result = _parse_simple_yaml(content)
            if result is not None:
                return result
        except Exception:
            pass
        print(f"⚠️ Warning: PyYAML not installed and fallback parsing failed for {file_path}")
        return None


def _parse_simple_yaml(content):
    """极简缩进 YAML 解析器"""
    lines = content.split('\n')
    clean_lines = []
    for line in lines:
        stripped = line.strip()
        if stripped == '' or stripped.startswith('#') or stripped.startswith('---'):
            continue
        clean_lines.append(line)
    if not clean_lines:
        return None
    try:
        intermediate = {}
        stack = [(intermediate, -1)]
        current_list_key = None
        for line in clean_lines:
            stripped = line.rstrip()
            if not stripped:
                continue
            indent = len(line) - len(line.lstrip(' '))
            while len(stack) > 1 and indent <= stack[-1][1]:
                stack.pop()
            parent, _ = stack[-1]
            if stripped.startswith('- '):
                val_str = stripped[2:].strip()
                val = _yaml_value(val_str)
                if isinstance(parent, list):
                    parent.append(val)
                else:
                    new_list = [val]
                    parent[current_list_key] = new_list
                    stack.append((new_list, indent))
                continue
            if ':' in stripped:
                key, _, val_str = stripped.partition(':')
                key = key.strip()
                val_str = val_str.strip()
                if val_str == '' or val_str == '{}' or val_str == '[]':
                    child = {} if (val_str == '' or val_str == '{}') else []
                    if isinstance(parent, dict):
                        parent[key] = child
                    stack.append((child, indent))
                    current_list_key = key
                else:
                    val = _yaml_value(val_str)
                    if isinstance(parent, dict):
                        parent[key] = val
                    elif isinstance(parent, list):
                        parent.append({key: val})
                    current_list_key = key
        return intermediate if intermediate else None
    except Exception:
        return None


def _yaml_value(val_str):
    val_str = val_str.strip()
    if not (val_str.startswith('"') or val_str.startswith("'")):
        if '#' in val_str:
            val_str = val_str.split('#')[0].strip()
    val_str = val_str.strip('"').strip("'")
    if val_str.lower() == 'true':
        return True
    if val_str.lower() == 'false':
        return False
    if val_str.lower() == 'null' or val_str == '':
        return None
    try:
        return int(val_str)
    except ValueError:
        pass
    try:
        return float(val_str)
    except ValueError:
        pass
    return val_str

def dump_yaml_or_json(data, file_path):
    os.makedirs(os.path.dirname(file_path), exist_ok=True)
    if file_path.endswith('.json'):
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        return
    try:
        import yaml
        with open(file_path, 'w', encoding='utf-8') as f:
            yaml.dump(data, f, allow_unicode=True, sort_keys=False)
    except ImportError:
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

def _get_nested_field(data, field_path):
    if not isinstance(data, dict) or not field_path:
        return None
    keys = str(field_path).split('.')
    curr = data
    for k in keys:
        if isinstance(curr, dict) and k in curr:
            curr = curr[k]
        else:
            return None
    return curr

class GraphRunner:
    def __init__(self, workspace_root="."):
        self.workspace_root = workspace_root
        self.autodev_dir = os.path.join(workspace_root, "autodev")
        self.state_file = os.path.join(self.autodev_dir, "state.yaml")
        self.status_file = os.path.join(self.autodev_dir, "status.json")
        self.active_workflow_file = os.path.join(self.autodev_dir, "workflows", "active-workflow.yaml")

    def _get_main_node_ids(self, workflow_data):
        """获取 workflow 中主路径节点 ID 列表（排除 bug_fix 回环辅助节点）"""
        return [n["id"] for n in workflow_data.get("nodes", []) if n["id"] != "bug_fix"]

    def _get_all_node_ids(self, workflow_data):
        """获取 workflow 中所有节点 ID（含辅助节点如 bug_fix），用于参数合法性校验"""
        return [n["id"] for n in workflow_data.get("nodes", [])]

    def init_workflow(self, template_path, run_id=None, force_skip=False, start_from=None, stop_after=None):
        """从模板初始化工作流图"""
        config_file = os.path.join(self.autodev_dir, "config.json")
        if not os.path.exists(config_file):
            print("❌ 前置条件未满足: autodev/config.json 不存在")
            print("   请先执行 Stage 0（项目架构自动识别）生成 config.json")
            sys.exit(1)

        config = load_yaml_or_json(config_file)
        if not config:
            print("❌ autodev/config.json 格式无效或为空")
            sys.exit(1)

        required = ["project", "modules", "database", "techStack", "ports", "api", "docs"]
        missing = [f for f in required if f not in config]
        if missing:
            print(f"❌ config.json 缺少必要字段: {missing}")
            sys.exit(1)

        warnings = []
        if config.get("database", {}).get("type", "") == "待确认":
            warnings.append("database.type 为'待确认'，下游阶段可能出错")
        if not config.get("project", {}).get("name", ""):
            warnings.append("project.name 为空")
        if warnings:
            print("⚠️ config.json 存在以下风险项:")
            for w in warnings:
                print(f"   - {w}")
            if not force_skip:
                print("   建议先修复再继续，或在确认安全后手动 --force-skip 绕过")
            else:
                print("   [--force-skip] 已生效，强行跳过警告阻断继续执行")

        if not run_id:
            run_id = get_now_utc8().strftime("%Y%m%d") + "-001"

        if not os.path.exists(template_path):
            script_dir = os.path.dirname(os.path.abspath(__file__))
            skill_root = os.path.abspath(os.path.join(script_dir, ".."))
            alt_path = os.path.join(skill_root, template_path)
            if os.path.exists(alt_path):
                template_path = alt_path
            else:
                raise FileNotFoundError(f"Template workflow not found: {template_path}")

        with open(template_path, 'r', encoding='utf-8') as f:
            raw_content = f.read()

        instantiated_content = raw_content.replace("{RUN}", run_id)
        os.makedirs(os.path.dirname(self.active_workflow_file), exist_ok=True)
        with open(self.active_workflow_file, 'w', encoding='utf-8') as f:
            f.write(instantiated_content)

        workflow_data = load_yaml_or_json(self.active_workflow_file)
        main_nodes = self._get_main_node_ids(workflow_data)
        all_node_ids = self._get_all_node_ids(workflow_data)

        # 确定起始节点
        first_node = main_nodes[0] if main_nodes else "unknown"
        if start_from:
            if start_from not in all_node_ids:
                print(f"❌ Invalid --start-from '{start_from}'. Valid nodes: {all_node_ids}")
                sys.exit(1)
            first_node = start_from

        # 构建 node_statuses ：
        # - start_from 之前 → skipped
        # - start_from → running
        # - start_from ~ stop_after 之间 → pending
        # - stop_after 之后 → skipped
        start_idx = main_nodes.index(first_node) if first_node in main_nodes else len(main_nodes)
        stop_idx = None
        if stop_after:
            if stop_after not in all_node_ids:
                print(f"❌ Invalid --stop-after '{stop_after}'. Valid nodes: {all_node_ids}")
                sys.exit(1)
            stop_idx = main_nodes.index(stop_after) if stop_after in main_nodes else None

        node_statuses = {}
        now_ts = get_now_utc8().isoformat()
        for i, nid in enumerate(main_nodes):
            if i < start_idx:
                node_statuses[nid] = {"status": "skipped", "started_at": now_ts, "completed_at": now_ts}
            elif nid == first_node:
                node_statuses[nid] = {"status": "running", "started_at": now_ts}
            elif stop_idx is not None and i > stop_idx:
                node_statuses[nid] = {"status": "skipped", "started_at": now_ts, "completed_at": now_ts}
            else:
                node_statuses[nid] = {"status": "pending"}

        # 辅助节点（如 bug_fix）作为 start_from 目标时，单独初始化
        if first_node not in main_nodes:
            node_statuses[first_node] = {"status": "running", "started_at": now_ts}

        initial_state = {
            "version": "2.0",
            "run_id": run_id,
            "workflow_name": workflow_data.get("name", "standard-flow"),
            "current_node": first_node,
            "overall_status": "RUNNING",
            "started_at": now_ts,
            "node_statuses": node_statuses,
            "edge_trigger_counts": {},
            "history": [],
            "start_from": start_from,
            "stop_after": stop_after
        }
        dump_yaml_or_json(initial_state, self.state_file)
        self.sync_status_json(initial_state, workflow_data)

        range_info = f"Start: {first_node}"
        if stop_after:
            range_info += f", Stop after: {stop_after}"
        print(f"✅ Workflow initialized successfully. Active RUN: {run_id}, {range_info}")

    def evaluate_condition(self, condition):
        contract_ref = condition.get("contract_ref")
        field = condition.get("field")
        operator = condition.get("operator")
        expected_val = condition.get("value")

        if not contract_ref or not os.path.exists(contract_ref):
            print(f"⚠️ Warning: Contract file '{contract_ref}' not found. Falling back to default edge.")
            return False

        contract_data = load_yaml_or_json(contract_ref)
        if not contract_data:
            return False

        actual_val = _get_nested_field(contract_data, field)
        if operator == "==":
            return actual_val == expected_val
        elif operator == "!=":
            return actual_val != expected_val
        elif operator == ">":
            return actual_val is not None and actual_val > expected_val
        elif operator == "<":
            return actual_val is not None and actual_val < expected_val
        elif operator == ">=":
            return actual_val is not None and actual_val >= expected_val
        elif operator == "<=":
            return actual_val is not None and actual_val <= expected_val
        elif operator == "in":
            return actual_val is not None and actual_val in expected_val
        elif operator == "not in":
            return actual_val is not None and actual_val not in expected_val
        else:
            print(f"⚠️ Warning: Unsupported operator '{operator}' in condition {condition}")
            return False

    def reset_workflow(self):
        if not os.path.exists(self.state_file):
            print("❌ 状态文件不存在，无需重置")
            return
        state = load_yaml_or_json(self.state_file)
        state["overall_status"] = "RUNNING"
        state["edge_trigger_counts"] = {}
        dump_yaml_or_json(state, self.state_file)
        workflow = load_yaml_or_json(self.active_workflow_file) if os.path.exists(self.active_workflow_file) else None
        self.sync_status_json(state, workflow)
        print(f"🔄 Workflow reset successfully. RUN: {state.get('run_id')}, Status: RUNNING, trigger counts cleared.")

    def next_step(self, stop_after=None):
        state = load_yaml_or_json(self.state_file)
        workflow = load_yaml_or_json(self.active_workflow_file)

        current_node_id = state.get("current_node")
        print(f"🔄 Evaluating transitions from current node: {current_node_id}")

        outgoing_edges = [e for e in workflow.get("edges", []) if e.get("from") == current_node_id]

        matched_next_node = None
        chosen_edge = None

        for edge in outgoing_edges:
            if "condition" in edge:
                edge_key = f"{edge['from']}->{edge['to']}"
                count = state.get("edge_trigger_counts", {}).get(edge_key, 0)
                max_count = edge.get("max_trigger_count", 99)
                if count >= max_count:
                    print(f"⚠️ Edge {edge_key} reached max trigger count ({max_count}). Triggering fuse guard!")
                    state["overall_status"] = "BLOCKED"
                    dump_yaml_or_json(state, self.state_file)
                    self.sync_status_json(state, workflow)
                    return
                if self.evaluate_condition(edge["condition"]):
                    matched_next_node = edge["to"]
                    chosen_edge = edge
                    break

        if not matched_next_node:
            for edge in outgoing_edges:
                if edge.get("is_default") or "condition" not in edge:
                    matched_next_node = edge["to"]
                    chosen_edge = edge
                    break

        if chosen_edge and (matched_next_node is None or str(matched_next_node).lower() == "null"):
            print(f"🚫 Terminal edge matched: {current_node_id} → null. Workflow paused.")
            state["overall_status"] = "BLOCKED"
            state["history"].append(current_node_id)
            dump_yaml_or_json(state, self.state_file)
            self.sync_status_json(state, workflow)
            return

        # stop-after 检测（在条件边评估完成后，确保契约结果已被正确评估）
        effective_stop = stop_after or state.get("stop_after")
        if effective_stop and current_node_id == effective_stop:
            state["node_statuses"][current_node_id]["status"] = "success"
            state["node_statuses"][current_node_id]["completed_at"] = get_now_utc8().isoformat()
            state["history"].append(current_node_id)
            if matched_next_node:
                print(f"⏹️ Stopped after '{current_node_id}' (stop-after={effective_stop}). Evaluated next: {matched_next_node}")
            else:
                print(f"⏹️ Stopped after '{current_node_id}' (stop-after={effective_stop}). No further transitions.")
            state["overall_status"] = "SUCCESS"
            dump_yaml_or_json(state, self.state_file)
            self.sync_status_json(state, workflow)
            return

        if matched_next_node:
            nodes_def = {n["id"]: n for n in workflow.get("nodes", [])}
            target_node_def = nodes_def.get(matched_next_node, {})
            required_inputs = target_node_def.get("inputs", [])
            missing_inputs = []
            for inp in required_inputs:
                full_path = inp if os.path.isabs(inp) else os.path.join(self.workspace_root, inp)
                if not os.path.exists(full_path):
                    missing_inputs.append(inp)

            if missing_inputs:
                # 若存在 start_from 且有被跳过的节点，对缺失契约仅警告不阻断
                skipped_nodes = [nid for nid, info in state.get("node_statuses", {}).items()
                                 if info.get("status") == "skipped"]
                if state.get("start_from") and skipped_nodes:
                    print(f"⚠️ Warning: Target node '{matched_next_node}' missing input contracts: {missing_inputs}")
                    print(f"   (Relaxed due to --start-from skipping nodes: {skipped_nodes})")
                else:
                    print(f"❌ Target node '{matched_next_node}' is not ready. Missing input contracts: {missing_inputs}")
                    print(f"   Please ensure the current stage Agent has written its output contract before calling 'next'.")
                    sys.exit(1)

            edge_key = f"{chosen_edge['from']}->{chosen_edge['to']}"
            state.setdefault("edge_trigger_counts", {})[edge_key] = state["edge_trigger_counts"].get(edge_key, 0) + 1
            state["history"].append(current_node_id)
            state["node_statuses"][current_node_id]["status"] = "success"
            state["node_statuses"][current_node_id]["completed_at"] = get_now_utc8().isoformat()

            state["current_node"] = matched_next_node
            state["node_statuses"][matched_next_node] = {
                "status": "running",
                "started_at": get_now_utc8().isoformat()
            }
            dump_yaml_or_json(state, self.state_file)
            self.sync_status_json(state, workflow)
            print(f"➡️ Transitioned: {current_node_id} -> {matched_next_node}")
        else:
            print(f"🏁 No outgoing edges from {current_node_id}. Workflow execution completed!")
            state["overall_status"] = "SUCCESS"
            dump_yaml_or_json(state, self.state_file)
            self.sync_status_json(state, workflow)

    def sync_status_json(self, state, workflow=None):
        """兼容映射输出 status.json 供 status.sh 读取"""
        stages_map = {}
        node_stage_mapping = {}
        if workflow:
            for node_def in workflow.get("nodes", []):
                stage_num = node_def.get("stage")
                if stage_num is not None:
                    node_stage_mapping[node_def["id"]] = f"Stage_{stage_num}"
        if not node_stage_mapping:
            node_stage_mapping = {
                "requirement": "Stage_1",
                "develop": "Stage_2",
                "bug_fix": "Stage_2",
                "review": "Stage_3",
                "code_review": "Stage_3",
                "test_audit": "Stage_4",
                "integration_test": "Stage_5",
                "gate_check": "Stage_6",
                "doc_engineer": "Stage_7",
                "doc_sync": "Stage_7"
            }

        # 已有节点状态
        for node_id, info in state.get("node_statuses", {}).items():
            stage_key = node_stage_mapping.get(node_id, f"Node_{node_id}")
            stages_map[stage_key] = {
                "status": info.get("status", "pending"),
                "message": f"Active node: {node_id}",
                "last_update": info.get("started_at", "")
            }

        # 补齐 workflow 中存在但 node_statuses 中缺失的节点（级联删除 state.yaml 等边缘情况）
        existing_node_ids = set(state.get("node_statuses", {}).keys())
        if workflow:
            for nid in self._get_main_node_ids(workflow):
                if nid in existing_node_ids:
                    continue
                stage_key = node_stage_mapping.get(nid, f"Node_{nid}")
                stages_map[stage_key] = {
                    "status": "pending",
                    "message": "Missing from node_statuses",
                    "last_update": ""
                }

        status_data = {
            "RUN": state.get("run_id"),
            "status": state.get("overall_status"),
            "current_stage": state.get("current_node"),
            "stages": stages_map
        }
        os.makedirs(os.path.dirname(self.status_file), exist_ok=True)
        with open(self.status_file, 'w', encoding='utf-8') as f:
            json.dump(status_data, f, ensure_ascii=False, indent=2)

    def show_status(self):
        if not os.path.exists(self.state_file):
            print("❌ 状态文件不存在，请先执行 init")
            sys.exit(1)

        state = load_yaml_or_json(self.state_file)
        run_id = state.get("run_id", "N/A")
        overall = state.get("overall_status", "N/A")
        current = state.get("current_node", "N/A")
        started = state.get("started_at", "N/A")
        stop_after = state.get("stop_after")
        start_from = state.get("start_from")

        status_icons = {"RUNNING": "⏳", "SUCCESS": "✅", "BLOCKED": "🚫"}
        icon = status_icons.get(overall, "❓")

        print(f"📊 AutoDev Flow 状态")
        print(f"   批次:     {run_id}")
        print(f"   状态:     {icon} {overall}")
        print(f"   当前节点: {current}")
        if start_from:
            print(f"   起始节点: {start_from}")
        if stop_after:
            print(f"   终止节点: {stop_after}")
        print(f"   启动时间: {started}")

        node_statuses = state.get("node_statuses", {})
        if node_statuses:
            print(f"\n📋 节点状态:")
            for node_id, info in node_statuses.items():
                ns = info.get("status", "pending")
                ns_icon = {"running": "🟠", "success": "✅", "skipped": "⏭️", "pending": "⏸️"}.get(ns, "❓")
                print(f"   {ns_icon} {node_id}: {ns}")

        counters = state.get("edge_trigger_counts", {})
        if counters:
            print(f"\n🔄 边触发计数:")
            for key, count in counters.items():
                print(f"   {key}: {count}")

        history = state.get("history", [])
        if history:
            print(f"\n📜 历史轨迹: {' → '.join(history)}")

    def _get_skill_dir(self):
        script_dir = os.path.dirname(os.path.abspath(__file__))
        return os.path.abspath(os.path.join(script_dir, ".."))

    def check_update(self):
        """检查 Skill 是否有最新版本 (产物隔离只读检查)"""
        skill_dir = self._get_skill_dir()
        git_dir = os.path.join(skill_dir, ".git")
        print(f"🔍 检查 Skill 更新: {skill_dir}")
        if not os.path.exists(git_dir):
            print("ℹ️ 当前 Skill 目录非 Git 仓库，无法自动检查 Remote 更新。建议重克隆升级。")
            return
        import subprocess
        try:
            res = subprocess.run(["git", "fetch"], cwd=skill_dir, capture_output=True, text=True, timeout=10)
            if res.returncode != 0:
                print(f"⚠️ Warning: git fetch 失败: {res.stderr.strip()}")
                return
            status_res = subprocess.run(["git", "status", "-uno"], cwd=skill_dir, capture_output=True, text=True)
            if "behind" in status_res.stdout:
                print("💡 发现新版本！可通过 python3 graph_runner.py upgrade 执行升级。")
            else:
                print("✅ 当前 Skill 已是最新版本。")
        except Exception as e:
            print(f"⚠️ Warning: 检查更新失败: {e}")

    def upgrade_skill(self):
        """执行 Skill 升级流程 (严格产物隔离原则)"""
        skill_dir = self._get_skill_dir()
        backup_dir = os.path.join(self.autodev_dir, ".backup")
        now_str = get_now_utc8().strftime("%Y%m%d_%H%M%S")
        snapshot_dir = os.path.join(backup_dir, f"skill_backup_{now_str}")

        print("🚀 开始 Skill 升级流程...")
        print("🔒 遵循 [产物隔离原则]: 严禁修改 autodev/contracts/、state.yaml 及项目配置！")

        if os.path.exists(self.state_file):
            state = load_yaml_or_json(self.state_file) or {}
            if state.get("overall_status") == "RUNNING":
                print("⚠️ 警告: 当前工作流处于 RUNNING 状态，请确保升级不破坏后续阶段接口。")

        import shutil
        try:
            os.makedirs(snapshot_dir, exist_ok=True)
            skill_backup_target = os.path.join(snapshot_dir, "autodev-flow")
            shutil.copytree(skill_dir, skill_backup_target, ignore=shutil.ignore_patterns("__pycache__", ".git", "autodev", ".backup"))
            print(f"📦 步骤 1/3: 技能源码快照备份完成 -> {snapshot_dir}")
        except Exception as e:
            print(f"❌ 备份技能源码失败: {e}")
            sys.exit(1)

        git_dir = os.path.join(skill_dir, ".git")
        if os.path.exists(git_dir):
            import subprocess
            try:
                res = subprocess.run(["git", "pull", "origin", "main"], cwd=skill_dir, capture_output=True, text=True, timeout=30)
                if res.returncode == 0:
                    print("⬇️ 步骤 2/3: Skill 源码更新成功 (Git pull origin main)")
                else:
                    print(f"⚠️ Git pull 提示: {res.stdout.strip()} {res.stderr.strip()}")
            except Exception as e:
                print(f"⚠️ Git pull 执行失败: {e}")
        else:
            print("ℹ️ 步骤 2/3: 非 Git 仓库模式，跳过 git pull")

        try:
            status_sh_src = os.path.join(skill_dir, "scripts", "status.sh")
            status_ps1_src = os.path.join(skill_dir, "scripts", "status.ps1")
            status_sh_dst = os.path.join(self.autodev_dir, "status.sh")
            status_ps1_dst = os.path.join(self.autodev_dir, "status.ps1")
            if os.path.exists(status_sh_src) and os.path.exists(self.autodev_dir):
                shutil.copy2(status_sh_src, status_sh_dst)
                os.chmod(status_sh_dst, 0o755)
            if os.path.exists(status_ps1_src) and os.path.exists(self.autodev_dir):
                shutil.copy2(status_ps1_src, status_ps1_dst)
            print("🔄 步骤 3/3: 公共看盘脚本 (status.sh / status.ps1) 刷新完成")
        except Exception as e:
            print(f"⚠️ Warning: 刷新看盘脚本失败: {e}")

        print("🎉 Skill 升级完成！已隔离保护所有 contracts 契约与历史状态文件。")

    def rollback_skill(self):
        """回滚 Skill 至最近的快照备份"""
        backup_dir = os.path.join(self.autodev_dir, ".backup")
        if not os.path.exists(backup_dir):
            print("❌ 未找到任何快照备份目录")
            return
        backups = sorted([d for d in os.listdir(backup_dir) if d.startswith("skill_backup_")])
        if not backups:
            print("❌ 未找到有效快照")
            return
        latest_backup = os.path.join(backup_dir, backups[-1], "autodev-flow")
        skill_dir = self._get_skill_dir()

        print(f"🔄 准备从快照回滚: {backups[-1]}")
        import shutil
        try:
            for item in os.listdir(latest_backup):
                s = os.path.join(latest_backup, item)
                d = os.path.join(skill_dir, item)
                if os.path.isdir(s):
                    if os.path.exists(d):
                        shutil.rmtree(d)
                    shutil.copytree(s, d)
                else:
                    shutil.copy2(s, d)
            print("✅ Skill 回滚完成！")
        except Exception as e:
            print(f"❌ 回滚失败: {e}")

if __name__ == "__main__":
    runner = GraphRunner()
    args = sys.argv[1:]
    force_skip = "--force-skip" in args
    args = [a for a in args if a != "--force-skip"]

    start_from = None
    stop_after = None
    filtered_args = []
    i = 0
    while i < len(args):
        if args[i] == "--start-from" and i + 1 < len(args):
            start_from = args[i + 1]
            i += 2
        elif args[i] == "--stop-after" and i + 1 < len(args):
            stop_after = args[i + 1]
            i += 2
        else:
            filtered_args.append(args[i])
            i += 1
    args = filtered_args

    if args:
        cmd = args[0]
        if cmd == "init":
            template = args[1] if len(args) > 1 else "template/workflows/standard-feature.workflow.yaml"
            run_id = args[2] if len(args) > 2 else None
            runner.init_workflow(template, run_id, force_skip=force_skip, start_from=start_from, stop_after=stop_after)
        elif cmd == "next":
            runner.next_step(stop_after=stop_after)
        elif cmd == "status":
            runner.show_status()
        elif cmd == "reset":
            runner.reset_workflow()
        elif cmd == "check-update":
            runner.check_update()
        elif cmd == "upgrade":
            runner.upgrade_skill()
        elif cmd == "rollback":
            runner.rollback_skill()
        else:
            print("Usage: python3 graph_runner.py [init <template_path> <run_id>] [--start-from <node_id>] [--stop-after <node_id>] [--force-skip] | next | status | reset | check-update | upgrade | rollback")
    else:
        print("Usage: python3 graph_runner.py [init <template_path> <run_id>] [--start-from <node_id>] [--stop-after <node_id>] [--force-skip] | next | status | reset | check-update | upgrade | rollback")

