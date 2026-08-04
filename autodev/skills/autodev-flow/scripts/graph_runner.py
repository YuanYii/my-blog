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
        # 极简 YAML 解析兜底 — 支持缩进 YAML（键值对、列表）
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        # 先尝试 JSON
        try:
            return json.loads(content)
        except Exception:
            pass
        # 再试裸 YAML 文本按行解析
        try:
            result = _parse_simple_yaml(content)
            if result is not None:
                return result
        except Exception:
            pass
        print(f"⚠️ Warning: PyYAML not installed and fallback parsing failed for {file_path}")
        return None


def _parse_simple_yaml(content):
    """极简缩进 YAML 解析器 — 支持键值对、列表、嵌套，足以处理 workflow.yaml 和 contracts"""
    lines = content.split('\n')
    # 跳过纯注释/空行/标记行
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
        stack = [(intermediate, -1)]  # (dict/list, indent)
        current_list_key = None
        for line in clean_lines:
            stripped = line.rstrip()
            if not stripped:
                continue
            indent = len(line) - len(line.lstrip(' '))
            # 弹出更深层级的栈帧
            while len(stack) > 1 and indent <= stack[-1][1]:
                stack.pop()
            parent, _ = stack[-1]
            # 列表项
            if stripped.startswith('- '):
                val_str = stripped[2:].strip()
                val = _yaml_value(val_str)
                if isinstance(parent, list):
                    parent.append(val)
                else:
                    # 当前在 dict 内但遇到了列表项 → 创建列表
                    new_list = [val]
                    parent[current_list_key] = new_list
                    stack.append((new_list, indent))
                continue
            # 键值对
            if ':' in stripped:
                key, _, val_str = stripped.partition(':')
                key = key.strip()
                val_str = val_str.strip()
                if val_str == '' or val_str == '{}' or val_str == '[]':
                    # 空值或容器标记 → 子对象
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
    """YAML 值类型推断（支持处理内联注释及基本标量清洗）"""
    val_str = val_str.strip()
    # 清理非引号包裹的内联注释
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
    """保存数据为 YAML/JSON 文件"""
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
        # 没有 PyYAML 时降级：如果格式为 yaml，输出带可读缩进的文本结构
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

def _get_nested_field(data, field_path):
    """支持点号级联（如 'metrics.critical' 或 'anomaly_counts.bug'）嵌套字段提取"""
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

    def init_workflow(self, template_path, run_id=None, force_skip=False):
        """从模板初始化工作流图"""
        # 前置检查：config.json 必须存在
        config_file = os.path.join(self.autodev_dir, "config.json")
        if not os.path.exists(config_file):
            print("❌ 前置条件未满足: autodev/config.json 不存在")
            print("   请先执行 Stage 0（项目架构自动识别）生成 config.json")
            print("   手动运行: 对 Agent 说 '使用 autodev-flow，检测项目架构'")
            sys.exit(1)

        # 验证 config.json 基本字段
        config = load_yaml_or_json(config_file)
        if not config:
            print("❌ autodev/config.json 格式无效或为空")
            print("   请重新执行 Stage 0 或手动修复 config.json")
            sys.exit(1)

        required = ["project", "modules", "database", "techStack", "ports", "api", "docs"]
        missing = [f for f in required if f not in config]
        if missing:
            print(f"❌ config.json 缺少必要字段: {missing}")
            print("   请重新执行 Stage 0 并确保校验通过")
            sys.exit(1)

        # 警告：关键字段为占位值
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

        # 替换占位符 {RUN}
        instantiated_content = raw_content.replace("{RUN}", run_id)
        
        # 写入 active-workflow.yaml
        os.makedirs(os.path.dirname(self.active_workflow_file), exist_ok=True)
        with open(self.active_workflow_file, 'w', encoding='utf-8') as f:
            f.write(instantiated_content)

        # 读取解析
        workflow_data = load_yaml_or_json(self.active_workflow_file)
        first_node = workflow_data["nodes"][0]["id"] if workflow_data.get("nodes") else "unknown"

        # 初始化 state.yaml
        initial_state = {
            "version": "2.0",
            "run_id": run_id,
            "workflow_name": workflow_data.get("name", "standard-flow"),
            "current_node": first_node,
            "overall_status": "RUNNING",
            "started_at": get_now_utc8().isoformat(),
            "node_statuses": {
                first_node: {"status": "running", "started_at": get_now_utc8().isoformat()}
            },
            "edge_trigger_counts": {},
            "history": []
        }
        dump_yaml_or_json(initial_state, self.state_file)
        self.sync_status_json(initial_state, workflow_data)
        print(f"✅ Workflow initialized successfully. Active RUN: {run_id}, Initial Node: {first_node}")

    def evaluate_condition(self, condition):
        """评估条件边的表达式"""
        contract_ref = condition.get("contract_ref")
        field = condition.get("field")
        operator = condition.get("operator")
        expected_val = condition.get("value")

        if not contract_ref or not os.path.exists(contract_ref):
            print(f"⚠️ Warning: Contract file '{contract_ref}' not found while evaluating condition {condition}. Falling back to default edge.")
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
            print(f"⚠️ Warning: Unsupported or invalid condition operator '{operator}' in condition {condition}")
            return False

    def reset_workflow(self):
        """重置工作流 BLOCKED 状态及边触发计数器"""
        if not os.path.exists(self.state_file):
            print("❌ 状态文件不存在，无需重置")
            return
        state = load_yaml_or_json(self.state_file)
        state["overall_status"] = "RUNNING"
        state["edge_trigger_counts"] = {}
        dump_yaml_or_json(state, self.state_file)
        # 尝试加载 workflow 以获取动态 stage 映射
        workflow = load_yaml_or_json(self.active_workflow_file) if os.path.exists(self.active_workflow_file) else None
        self.sync_status_json(state, workflow)
        print(f"🔄 Workflow reset successfully. RUN: {state.get('run_id')}, Status: RUNNING, trigger counts cleared.")

    def next_step(self):
        """推演下一个就绪/转换节点"""
        state = load_yaml_or_json(self.state_file)
        workflow = load_yaml_or_json(self.active_workflow_file)

        current_node_id = state.get("current_node")
        print(f"🔄 Evaluating transitions from current node: {current_node_id}")

        # 查找以 current_node 为源的边
        outgoing_edges = [e for e in workflow.get("edges", []) if e.get("from") == current_node_id]

        matched_next_node = None
        chosen_edge = None

        # 优先匹配条件边
        for edge in outgoing_edges:
            if "condition" in edge:
                # 检查最大触发次数防死循环
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

        # 若未匹配到条件边，寻找默认边 (is_default: True) 或普通无条件边
        if not matched_next_node:
            for edge in outgoing_edges:
                if edge.get("is_default") or "condition" not in edge:
                    matched_next_node = edge["to"]
                    chosen_edge = edge
                    break

        if chosen_edge and (matched_next_node is None or str(matched_next_node).lower() == "null"):
            # 终止边（to: null）→ 工作流暂停
            print(f"🚫 Terminal edge matched: {current_node_id} → null. Workflow paused.")
            state["overall_status"] = "BLOCKED"
            state["history"].append(current_node_id)
            dump_yaml_or_json(state, self.state_file)
            self.sync_status_json(state, workflow)
            return

        if matched_next_node:
            # 节点就绪检测：检查目标节点所需的前置输入契约是否均已产生
            nodes_def = {n["id"]: n for n in workflow.get("nodes", [])}
            target_node_def = nodes_def.get(matched_next_node, {})
            required_inputs = target_node_def.get("inputs", [])
            missing_inputs = []
            for inp in required_inputs:
                full_path = inp if os.path.isabs(inp) else os.path.join(self.workspace_root, inp)
                if not os.path.exists(full_path):
                    missing_inputs.append(inp)

            if missing_inputs:
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
        # 动态构建节点→Stage 映射（从 workflow 模板的 stage 字段读取）
        node_stage_mapping = {}
        if workflow:
            for node_def in workflow.get("nodes", []):
                stage_num = node_def.get("stage")
                if stage_num is not None:
                    node_stage_mapping[node_def["id"]] = f"Stage_{stage_num}"
        # 兜底：如果 workflow 无 stage 字段或未传入 workflow，使用硬编码映射
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

        for node_id, info in state.get("node_statuses", {}).items():
            stage_key = node_stage_mapping.get(node_id, f"Node_{node_id}")
            stages_map[stage_key] = {
                "status": info.get("status", "pending"),
                "message": f"Active node: {node_id}",
                "last_update": info.get("started_at", "")
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
        """显示当前工作流状态摘要"""
        if not os.path.exists(self.state_file):
            print("❌ 状态文件不存在，请先执行 init")
            sys.exit(1)

        state = load_yaml_or_json(self.state_file)
        run_id = state.get("run_id", "N/A")
        overall = state.get("overall_status", "N/A")
        current = state.get("current_node", "N/A")
        started = state.get("started_at", "N/A")

        status_icons = {"RUNNING": "⏳", "SUCCESS": "✅", "BLOCKED": "🚫"}
        icon = status_icons.get(overall, "❓")

        print(f"📊 AutoDev Flow 状态")
        print(f"   批次:     {run_id}")
        print(f"   状态:     {icon} {overall}")
        print(f"   当前节点: {current}")
        print(f"   启动时间: {started}")

        # 节点状态
        node_statuses = state.get("node_statuses", {})
        if node_statuses:
            print(f"\n📋 节点状态:")
            for node_id, info in node_statuses.items():
                ns = info.get("status", "pending")
                ns_icon = {"running": "🟠", "success": "✅", "pending": "⏸️"}.get(ns, "❓")
                print(f"   {ns_icon} {node_id}: {ns}")

        # 边触发计数
        counters = state.get("edge_trigger_counts", {})
        if counters:
            print(f"\n🔄 边触发计数:")
            for key, count in counters.items():
                print(f"   {key}: {count}")

        # 历史
        history = state.get("history", [])
        if history:
            print(f"\n📜 历史轨迹: {' → '.join(history)}")

if __name__ == "__main__":
    runner = GraphRunner()
    args = sys.argv[1:]
    force_skip = "--force-skip" in args
    args = [a for a in args if a != "--force-skip"]

    if args:
        cmd = args[0]
        if cmd == "init":
            template = args[1] if len(args) > 1 else "template/workflows/standard-feature.workflow.yaml"
            run_id = args[2] if len(args) > 2 else None
            runner.init_workflow(template, run_id, force_skip=force_skip)
        elif cmd == "next":
            runner.next_step()
        elif cmd == "status":
            runner.show_status()
        elif cmd == "reset":
            runner.reset_workflow()
        else:
            print("Usage: python3 graph_runner.py [init <template_path> <run_id> [--force-skip] | next | status | reset]")
    else:
        print("Usage: python3 graph_runner.py [init <template_path> <run_id> [--force-skip] | next | status | reset]")
