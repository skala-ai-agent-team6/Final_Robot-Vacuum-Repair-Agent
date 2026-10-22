"""진단 그래프. 위에서 아래로 실행 순서대로 적었다.

  classify_photos → split_text → [pcb_mcp, mech_mcp 중 필요한 것 동시에] → rag_mcp → judge
      judge → report(완료) | request_photo(사진 요청) | fail(실패) → 끝

노드는 state(바구니)를 읽고, 바꿀 값만 dict로 돌려준다.
Spring에 진행 상황을 알릴 땐 emit(config, 이벤트종류, 내용)을 부른다.
"""

import os
from typing import Annotated, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt

from . import config as C
from . import tools
from . import enrichment
from .harness import LimitExceeded, observed
from .tool_policy import (check_image_result, check_object,
                          failure_output, limitation_note)


def finding_key(finding: dict):
    """같은 대상의 결과를 가리키는 키. 사진 분석은 사진 단위, 해석·검색은 도구 단위다."""
    source = finding.get("source")
    if source in ("PCB", "MECH", "CLASSIFICATION"):
        return (source, finding.get("attachmentId"))
    return (source, finding.get("output", {}).get("tool"))


def merge_findings(old: list[dict], new: list[dict]) -> list[dict]:
    """이어 붙이지 않고 키 기준으로 교체한다.

    체크포인터로 이전 라운드 상태가 남아 있을 때 같은 사진이 두 번 집계되는 것과,
    재시도로 성공한 뒤에도 이전 실패 기록이 남아 판정을 막는 것을 함께 막는다.
    """
    merged = {finding_key(f): f for f in old}
    merged.update({finding_key(f): f for f in new})
    return list(merged.values())


class State(TypedDict, total=False):
    command: dict  # Kafka로 받은 명령 원문
    text: str  # 증상 + 기사 설명
    photos: list[dict]  # [{attachmentId, path, kind: PCB|MECH|OTHER}]
    clues: dict  # {pcb: [], mech: [], sw: [], errorCodes: []}
    findings: Annotated[list[dict], merge_findings]  # MCP 결과. 같은 대상은 최신 결과로 교체한다
    decision: str  # COMPLETE | PHOTO | FAIL
    result: dict  # Spring에 보낼 마지막 이벤트 {eventType, payload}


async def emit(config, event_type: str, payload: dict):
    await config["configurable"]["emit"](event_type, payload)


# ── 1. 사진 분류 ─────────────────────────────────────────


async def classify_photos(state: State, config) -> dict:
    await emit(config, "DIAGNOSIS_PROGRESS", {"stage": "CLASSIFYING_PHOTOS"})
    photos = await tools.load_photos(state["command"])
    # 재개한 경우 이미 분석이 끝난 사진은 다시 모델에 넣지 않는다.
    analyzed = {f.get("attachmentId") for f in state.get("findings", [])
                if f.get("source") in ("PCB", "MECH") and not f["output"].get("error")}
    photos = [p for p in photos if p["attachmentId"] not in analyzed]
    failures = []
    for p in photos:
        # 이미 구분해 접수한 사진은 그 구분을 사용. GENERAL/미지정은 원본 GPT 분류 유지.
        p["kind"] = {"PCB": "PCB", "MOTOR": "MECH", "MECHANICAL": "MECH"}.get(p.get("target"))
        if p["kind"] is None:
            try:
                p["kind"] = await tools.classify_photo(p["path"])
            except LimitExceeded:
                raise
            except Exception as exc:
                p["kind"] = "OTHER"
                out = failure_output("classify_photo", exc, required=True)
                failures.append({"source": "CLASSIFICATION", "attachmentId": p["attachmentId"],
                                 "found": False, "output": out})
                await emit(config, "TOOL_EXECUTED", {"tool": "classify_photo", "toolVersion": "1",
                           "agentType": "RAG", "mcpServer": "agent-llm", "input": {}, "output": out})
    # 이전 라운드에서 분류가 실패했던 사진이 이번에 성공하면, 그 실패 기록을 덮는다.
    failed_before = {f.get("attachmentId") for f in state.get("findings", [])
                     if f.get("source") == "CLASSIFICATION" and f.get("output", {}).get("error")}
    resolved = [{"source": "CLASSIFICATION", "attachmentId": p["attachmentId"], "found": False,
                 "output": {"tool": "classify_photo", "status": "ok"}}
                for p in photos
                if p["attachmentId"] in failed_before and p["kind"] != "OTHER"]
    return {"photos": photos, "findings": failures + resolved}


# ── 2. 글 분리 ───────────────────────────────────────────


async def split_text(state: State, config) -> dict:
    await emit(config, "DIAGNOSIS_PROGRESS", {"stage": "SPLITTING_TEXT"})
    repair = state["command"]["payload"]["repair"]
    text = f"{repair.get('symptom') or ''} {repair.get('user_input', {}).get('customerDescription', '')}".strip()
    clues = (await tools.split_text(text)).model_dump()
    clues["errorCodes"] = repair.get("error_codes") or []
    observation = await supplemental(config, "normalize_observation", "observation-mcp",
                                     lambda: enrichment.normalize_observation(text))
    return {"text": text, "clues": clues, "findings": [observation]}


async def supplemental(config, name, server, call):
    """추가 해석은 근거만 보강한다. 성공/실패로 원본의 found 판단을 바꾸지 않는다."""
    try:
        out = check_object(await call(), name)
    except LimitExceeded:
        raise
    except Exception as exc:
        out = failure_output(name, exc, required=False)
    await emit(config, "TOOL_EXECUTED", {"tool": name, "toolVersion": "1", "agentType": "RAG",
                                        "mcpServer": server, "input": {}, "output": out})
    return {"source": "INTERPRETATION", "attachmentId": None, "found": False,
            "output": {"tool": name, **out}}


# ── 3. MCP 고르기 + 호출 (동시에) ────────────────────────


def pick_mcps(state: State) -> list[str]:
    """부를 사진 MCP 노드 목록. 여러 개면 LangGraph가 동시에 실행한다. 사진 MCP가 없으면 바로 rag로."""
    kinds = {p["kind"] for p in state["photos"]}
    picked = []
    if "PCB" in kinds:
        picked.append("pcb_mcp")
    if "MECH" in kinds:
        picked.append("mech_mcp")
    return picked or ["rag_mcp"]


async def run_photo_mcp(state, config, kind: str, call, tool: str, agent_type: str, server: str) -> dict:
    """kind 사진마다 MCP를 부르고, Spring에 TOOL_EXECUTED를 알린다. pcb_mcp·mech_mcp가 같이 쓴다."""
    findings = []
    for p in [p for p in state["photos"] if p["kind"] == kind]:
        try:
            out = check_image_result(await call(p["path"]), tool)
        except LimitExceeded:
            raise
        except Exception as e:
            # 선택된 이미지 분석은 필수. 서버 장애를 '검출 없음'으로 넘기지 않는다.
            out = failure_output(tool, e, required=True)
            await emit(config, "TOOL_EXECUTED", {"tool": out["tool"], "toolVersion": "1",
                       "agentType": agent_type, "mcpServer": server,
                       "input": {"path": p["path"]}, "output": out})
            findings.append({"source": kind, "attachmentId": p["attachmentId"],
                             "found": False, "output": out})
            continue  # 같은 부위의 다음 사진과 병렬 분석을 계속 수집한다.
        await emit(config, "TOOL_EXECUTED", {"tool": tool, "toolVersion": "1", "agentType": agent_type,
                                             "mcpServer": server, "input": {"path": p["path"]}, "output": out})
        findings.append({"source": kind, "attachmentId": p["attachmentId"], "found": bool(out.get("is_defective")), "output": out})
    if kind == "PCB":
        labels = sorted({d["type"] for f in findings for d in f["output"].get("defects", [])})
        if labels:
            findings.append(await supplemental(config, "interpret_pcb", "pcb-mcp",
                                                lambda: enrichment.interpret_pcb(labels)))
    else:
        # [통합] 표면 결함 라벨(crazing 등)을 그대로 해석에 보낸다. 새 Rag_mcp는 옛 dent/부위 형식을 미등록으로 둔다
        labels = sorted({label for f in findings for label in enrichment.surface_labels(f["output"])})
        if labels:
            findings.append(await supplemental(config, "interpret_damage", "damage-mcp",
                                                lambda: enrichment.interpret_damage(labels)))
    return {"findings": findings}


async def pcb_mcp(state: State, config) -> dict:
    await emit(config, "DIAGNOSIS_PROGRESS", {"stage": "PCB_ANALYSIS"})  # [통합] 프론트 진행 화면 단계 이름
    return await run_photo_mcp(state, config, "PCB", tools.call_pcb_mcp, "analyze_pcb_image", "PCB", "pcb-detector")


async def mech_mcp(state: State, config) -> dict:
    await emit(config, "DIAGNOSIS_PROGRESS", {"stage": "MOTOR_ANALYSIS"})  # [통합] 프론트 진행 화면 단계 이름
    return await run_photo_mcp(state, config, "MECH", tools.call_mech_mcp, "detect_surface_defects", "MOTOR", "mechanical-mcp")


async def rag_mcp(state: State, config) -> dict:
    """사진 MCP가 끝난 뒤 실행: 증상 글 + PCB가 찾은 라벨로 과거 사례 검색."""
    await emit(config, "DIAGNOSIS_PROGRESS", {"stage": "SIMILAR_CASE_SEARCH"})  # [통합] 프론트 진행 화면 단계 이름
    codes = state["clues"]["errorCodes"]
    text = state["text"] + (f" 에러코드 {', '.join(codes)}" if codes else "")
    pcb_labels = sorted({d["type"] for f in state.get("findings", []) if f["source"] == "PCB"
                         for d in f["output"].get("defects", [])})
    # [통합] 기계 MCP 표면 결함도 RAG 검색에 넘긴다 (새 Rag_mcp가 지원)
    damage_labels = sorted({label for f in state.get("findings", []) if f["source"] == "MECH"
                            for label in enrichment.surface_labels(f["output"])})
    try:
        out = check_object(await tools.call_rag_mcp(text, pcb_labels, damage_labels), "retrieve_repair_context")
    except LimitExceeded:
        raise
    except Exception as e:  # 보조 검색 실패는 기록하고 리포트에도 누락을 명시한다.
        out = {"hits": [], **failure_output("retrieve_repair_context", e, required=False)}
    await emit(config, "TOOL_EXECUTED", {"tool": "retrieve_repair_context", "toolVersion": "1", "agentType": "RAG",
                                         "mcpServer": "rag-mcp", "input": {"text": text, "pcbLabels": pcb_labels}, "output": out})
    return {"findings": [{"source": "RAG", "attachmentId": None, "found": False, "output": out}]}


# ── 4. 판단 ──────────────────────────────────────────────


def text_evidence(findings: list[dict]) -> bool:
    """사진 근거가 없을 때만 쓰는 차선 근거. 표준화된 소견이나 검색된 사례가 있어야 한다."""
    return any(f.get("source") == "RAG" and f.get("output", {}).get("hits") for f in findings) or \
           any(f.get("source") == "INTERPRETATION" and f.get("output", {}).get("observations") for f in findings)


def decide(findings: list[dict], photo_round: int) -> str:
    """근거가 충분한가? TODO(오케스트레이션): GPT 판단으로 바꿀 수 있다."""
    found = any(f["found"] for f in findings)
    # 이미지 분석 실패는 정상/결함 없음과 구분한다. 다른 성공 결과는 보존하되 재입력을 요청한다.
    if any(f.get("source") in ("PCB", "MECH", "CLASSIFICATION")
           and f.get("output", {}).get("error") for f in findings):
        if photo_round < C.MAX_PHOTO_ROUNDS:
            return "PHOTO"
        # 재요청을 다 쓴 뒤에도 실패가 남으면, 남은 근거만으로 리포트를 낸다.
        # 미확인 영역은 report 노드가 요약에 명시한다.
        return "COMPLETE" if found or text_evidence(findings) else "FAIL"
    if found:
        return "COMPLETE"  # 사진에서 결함을 하나라도 찾음
    if photo_round < C.MAX_PHOTO_ROUNDS:
        return "PHOTO"  # 못 찾음 + 아직 요청 가능
    # 3회를 다 요청했는데도 사진 근거가 없으면 소견·검색 근거만으로 작성한다.
    return "COMPLETE" if text_evidence(findings) else "FAIL"


async def judge(state: State, config) -> dict:
    await emit(config, "DIAGNOSIS_PROGRESS", {"stage": "JUDGING"})
    photo_round = state["command"]["payload"]["round"]
    # [통합·시연용] DEMO_FORCE_PHOTO=1이면 첫 진단은 무조건 추가 사진 요청. 기계 모델이 거의 항상 뭔가를 검출해서 요청 흐름이 안 보였다
    if os.getenv("DEMO_FORCE_PHOTO") == "1" and photo_round == 0:
        return {"decision": "PHOTO"}
    return {"decision": decide(state["findings"], photo_round)}


# ── 5. 끝: 리포트 / 사진 요청 / 실패 ─────────────────────


async def report(state: State, config) -> dict:
    parts = state["command"]["payload"]["repair"].get("product_snapshot", {}).get("compatibleParts", [])
    r = await tools.write_report(state["text"], state["findings"], parts)
    # [통합] 점수 높은 순으로 순위를 다시 매긴다. GPT가 준 순서 그대로면 0.79가 1순위, 0.95가 2순위가 되기도 했다
    ranked = sorted((c.model_dump() for c in r.candidates), key=lambda c: -c["confidence"])
    candidates = [{"rank": i + 1, **c} for i, c in enumerate(ranked)]
    evidence = []  # 근거는 코드가 MCP 결과에서 직접 만든다 (GPT가 사진 번호를 지어내지 않게)
    for f in state["findings"]:
        if f["found"]:
            e = {"type": "PHOTO", "annotation": {"defects": f["output"]["defects"], "image": f["output"]["annotated_image"]}}
            if f["output"].get("dent_image"):  # 기계 사진은 찌그러짐 박스 사진이 하나 더 있다
                e["annotation"]["dentImage"] = f["output"]["dent_image"]
            if f["attachmentId"]:  # 예시 사진은 Spring 첨부가 아니라 번호가 없다
                e["attachmentId"] = f["attachmentId"]
            evidence.append(e)
        if f["source"] == "RAG":
            evidence += [{"type": "MANUAL" if h["kind"] in ("LABEL", "REFERENCE") else "CASE",
                          **{k: v for k, v in h.items() if k != "kind"}} for h in f["output"]["hits"]]
    payload = {"summary": r.summary, "modelVersion": C.MODEL_VERSION, "repairMethod": r.repairMethod,
               "candidates": candidates, "recommendedParts": [p.model_dump() for p in r.recommendedParts],
               "evidence": evidence, "analysisResults": analysis_summary(state)["analysisResults"],
               **(await screen_fields(state, candidates))}
    if not any(e["type"] == "PHOTO" for e in evidence):
        payload["summary"] += ("\n\n사진 근거 없이 소견·매뉴얼 검색만으로 작성한 리포트입니다. "
                               "검색 유사도는 고장 확률이 아니므로 수리 전 해당 부위를 직접 확인해 주세요.")
    note = limitation_note(state["findings"])
    if note:
        payload["summary"] += "\n\n" + note  # LLM이 누락해도 최종 JSON·리포트에 반드시 남긴다.
    return {"result": {"eventType": "DIAGNOSIS_COMPLETED", "payload": payload}}


async def screen_fields(state, candidates) -> dict:
    """[통합] 프론트 진단 화면용 추가 필드 (기존 필드는 그대로).
    agents: 사진 위 박스. 프론트는 이미지 대비 0~1 좌표를 쓰는데 MCP는 픽셀 좌표라 사진 크기로 나눈다.
    similarCases: RAG 사례. 원래 evidence 안에만 있어 화면 '유사 사례' 칸이 비었다."""
    from PIL import Image
    paths = {p["attachmentId"]: p["path"] for p in state.get("photos", [])}
    agents = []
    for f in state["findings"]:
        if f["source"] not in ("PCB", "MECH") or not f["attachmentId"]:
            continue
        try:
            w, h = Image.open(paths[f["attachmentId"]]).size
        except (KeyError, OSError):  # 사진 파일이 없으면(테스트 대역 등) 박스 없이 넘긴다
            continue
        defects = f["output"].get("defects", [])
        agents.append({"agent": "PCB" if f["source"] == "PCB" else "MOTOR", "verdict": "ABNORMAL" if f["found"] else "NORMAL",
                       "summary": ", ".join(sorted({d["type"] for d in defects})) or "검출 없음",
                       "findings": [f"{d['type']} {d['confidence']:.2f}" for d in defects],
                       "confidence": max((d["confidence"] for d in defects), default=0), "attachmentIds": [f["attachmentId"]],
                       "annotations": [{"attachmentId": f["attachmentId"], "label": d["type"], "score": d["confidence"],
                                        "x": d["box_xyxy"][0] / w, "y": d["box_xyxy"][1] / h,
                                        "width": (d["box_xyxy"][2] - d["box_xyxy"][0]) / w,
                                        "height": (d["box_xyxy"][3] - d["box_xyxy"][1]) / h} for d in defects if d.get("box_xyxy")]})
    cases = [{"caseId": h["citation"], "origin": "EXTERNAL", "sourceUri": h.get("sourceUri"), "summaryKo": h["excerpt"],
              "similarity": h.get("score", 0)} for f in state["findings"] if f["source"] == "RAG"
             for h in f["output"].get("hits", []) if h["kind"] == "CASE"]
    for c, ko in zip(cases, await tools.translate_cases([c["summaryKo"] for c in cases])):
        c["originalText"], c["summaryKo"] = c["summaryKo"], ko
    return {"topCause": candidates[0]["cause"] if candidates else None, "agents": agents, "similarCases": cases}


def analysis_summary(state):
    """재입력/종료에도 실제 수집 결과를 남긴다. 도구 실패를 정상으로 표시하지 않는다."""
    rows = []
    for f in state.get("findings", []):
        out = f["output"]
        rows.append({"source": f["source"], "attachmentId": f.get("attachmentId"),
                     "status": "ERROR" if out.get("error") else "DETECTED" if f["found"] else "NO_DETECTION"
                               if f["source"] in ("PCB", "MECH") else "REFERENCE",
                     "labels": sorted({d["type"] for d in out.get("defects", [])}),
                     "errorCode": out.get("errorCode"), "tool": out.get("tool"),
                     "referenceCount": len(out.get("hits", []))})
    detected = sum(r["status"] == "DETECTED" for r in rows)
    errors = sum(r["status"] == "ERROR" for r in rows)
    summary = f"사진 {detected}건에서 결함 단서를 수집했습니다. 분석·보조 도구 오류 {errors}건. 진단은 아직 확정되지 않았습니다."
    return {"summary": summary, "analysisResults": rows}


async def request_photo(state: State, config) -> dict:
    cmd = state["command"]
    failed = {f["source"] for f in state.get("findings", []) if f["output"].get("error")}
    kinds = failed & {"PCB", "MECH"} or {p["kind"] for p in state.get("photos", [])} & {"PCB", "MECH"}
    target = "PCB" if kinds == {"PCB"} else "MOTOR" if kinds == {"MECH"} else "GENERAL"
    instruction = {"PCB": "PCB 전체와 결함 의심 부위를 밝고 선명하게 촬영하고 증상을 보충해 주세요.",
                   "MOTOR": "기계·모터의 이상 부위를 밝고 선명하게 촬영하고 작동 증상을 보충해 주세요.",
                   "GENERAL": "고장 의심 부위의 사진과 작동 증상을 추가해 주세요."}[target]
    details = analysis_summary(state)
    description = details["summary"] + "\n" + instruction
    if failed & {"PCB", "MECH", "CLASSIFICATION"}:
        description += "\n분석 도구 오류로 확인하지 못한 자료가 있습니다. 사진 자체의 문제로 확정한 것은 아니며 서비스 복구도 필요할 수 있습니다."
    note = limitation_note(state["findings"])
    if note:
        description += "\n" + note
    payload = {"round": cmd["payload"]["round"] + 1, "bodyPart": target,
               "description": description, **details,
               # 재개할 상태를 가리키는 값. Spring이 보관했다가 재개 명령에 그대로 실어 돌려준다.
               "checkpoint": config["configurable"]["thread_id"]}
    return {"result": {"eventType": "PHOTO_REQUESTED", "payload": payload}}


async def wait_input(state: State, config) -> dict:
    """추가 자료가 올 때까지 멈춘다.

    재개하면 이 노드가 처음부터 다시 실행되므로 횟수 증가·이벤트 발행 같은 부작용은
    앞 노드와 워커에 두고 여기서는 멈추기만 한다. 재개되면 이전 결과를 비우고
    새 자료를 포함해 다시 분석한다.
    """
    interrupt(state["result"]["payload"])
    return {"result": None}


async def fail(state: State, config) -> dict:
    details = analysis_summary(state)
    details["summary"] += " 추가 자료 요청 3회 후에도 진단을 완료하지 못했습니다. 수동 점검이 필요합니다."
    return {"result": {"eventType": "DIAGNOSIS_FAILED", "payload": {
        "errorCode": "INCONCLUSIVE_AFTER_THIRD_PHOTO", **details}}}


# ── 조립 ────────────────────────────────────────────────


def build_graph(checkpointer=None):
    g = StateGraph(State)
    for fn in (classify_photos, split_text, pcb_mcp, mech_mcp, rag_mcp, judge, report,
               request_photo, wait_input, fail):
        g.add_node(fn.__name__, observed(fn))

    g.add_edge(START, "classify_photos")
    g.add_edge("classify_photos", "split_text")
    g.add_conditional_edges("split_text", pick_mcps, ["pcb_mcp", "mech_mcp", "rag_mcp"])  # 여기서 갈라져서 동시 실행
    for mcp in ("pcb_mcp", "mech_mcp"):
        g.add_edge(mcp, "rag_mcp")  # 사진 MCP가 다 끝나면 rag에서 합류 (PCB 라벨을 넘기려고)
    g.add_edge("rag_mcp", "judge")
    g.add_conditional_edges("judge", lambda s: s["decision"], {"COMPLETE": "report", "PHOTO": "request_photo", "FAIL": "fail"})
    for end in ("report", "fail"):
        g.add_edge(end, END)
    if checkpointer is None:
        # 멈춤은 상태를 저장할 수 있을 때만 가능하다. 없으면 기존처럼 결과를 내고 끝낸다.
        g.add_edge("request_photo", END)
    else:
        g.add_edge("request_photo", "wait_input")
        g.add_edge("wait_input", "classify_photos")  # 재개하면 새 자료로 다시 분석
    return g.compile(checkpointer=checkpointer)
