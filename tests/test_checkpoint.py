"""체크포인터: 같은 작업의 상태가 라운드를 넘어 남는지 확인한다."""
import asyncio
import copy
import uuid
from unittest.mock import AsyncMock

from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from worker import graph, harness
from worker.harness import thread_id
from test_integration import command, fixture_tools, Producer  # noqa: F401


def test_thread_id_prefers_returned_checkpoint():
    cmd = command()
    assert thread_id(cmd) == str(cmd["taskId"])          # 최초 요청은 작업 ID
    cmd["payload"]["checkpoint"] = "task-7:abc"
    assert thread_id(cmd) == "task-7:abc"                # 재개는 돌려받은 값을 따른다


def test_same_thread_keeps_earlier_findings_without_duplicates(fixture_tools):
    async def run():
        async with AsyncSqliteSaver.from_conn_string(":memory:") as saver:
            app = graph.build_graph(saver)
            first = await harness.invoke(app, command(), AsyncMock())
            again = copy.deepcopy(command())
            again["eventId"] = str(uuid.uuid4())         # 같은 작업의 다음 이벤트
            second = await harness.invoke(app, again, AsyncMock())
            return first, second

    first, second = asyncio.run(run())
    # 같은 사진을 다시 분석해도 결과가 쌓이지 않는다
    assert len(second["findings"]) == len(first["findings"])
    keys = [graph.finding_key(f) for f in second["findings"]]
    assert len(keys) == len(set(keys))


def test_without_checkpointer_each_run_starts_empty(fixture_tools):
    async def run():
        app = graph.build_graph()                        # 체크포인터 없음
        return await harness.invoke(app, command(), AsyncMock())

    state = asyncio.run(run())
    assert state["findings"], "체크포인터가 없어도 한 번의 실행은 정상 동작해야 한다"


def test_pauses_for_more_photos_and_resumes_without_reanalyzing(fixture_tools):
    """1차에서 결함을 못 찾으면 멈추고, 추가 사진으로 재개해 진단을 마친다."""
    from worker import tools
    clean = {**fixture_tools, "is_defective": False, "defects": []}
    tools.call_pcb_mcp.return_value = clean

    async def run():
        async with AsyncSqliteSaver.from_conn_string(":memory:") as saver:
            app = graph.build_graph(saver)
            first = await harness.invoke(app, command(), AsyncMock())

            paused = first.get("__interrupt__")
            assert paused, "결함을 못 찾았으면 추가 자료를 기다리며 멈춰야 한다"
            asked = paused[0].value

            # 추가 사진이 들어오고, 이번에는 결함이 보인다
            tools.call_pcb_mcp.return_value = {**fixture_tools, "is_defective": True,
                                               "defects": [{"type": "short", "confidence": .8}]}
            resumed = copy.deepcopy(command())
            resumed["eventId"] = str(uuid.uuid4())
            resumed["eventType"] = "DIAGNOSIS_RESUMED"
            resumed["payload"]["round"] = 1
            resumed["payload"]["checkpoint"] = asked["checkpoint"]
            resumed["payload"]["attachments"] += [{"id": "2", "target": "PCB"}]
            second = await harness.invoke(app, resumed, AsyncMock(), resume=resumed["payload"])
            return asked, second

    asked, second = asyncio.run(run())
    assert asked["checkpoint"] == str(command()["taskId"])      # 재개 키를 그대로 돌려준다
    assert asked["round"] == 1
    assert second["result"]["eventType"] == "DIAGNOSIS_COMPLETED"
    # 1차 사진은 이미 분석했으므로 재개 때 다시 부르지 않는다 (1차 1회 + 추가 1회)
    from worker import tools as t
    assert t.call_pcb_mcp.await_count == 2


def test_worker_emits_pause_event_then_resumes_on_next_command(fixture_tools, tmp_path):
    """워커 경로: 멈춤은 PHOTO_REQUESTED로 나가고, 다음 명령은 멈춘 지점에서 이어진다."""
    from worker import main, tools
    from worker.journal import Journal
    tools.call_pcb_mcp.return_value = {**fixture_tools, "is_defective": False, "defects": []}

    async def run():
        async with AsyncSqliteSaver.from_conn_string(":memory:") as saver:
            app = graph.build_graph(saver)
            journal, producer = Journal(tmp_path / "state.db"), Producer()
            try:
                first = await main.handle(command(), producer, journal, graph=app)
                tools.call_pcb_mcp.return_value = {**fixture_tools, "is_defective": True,
                                                   "defects": [{"type": "short", "confidence": .8}]}
                resumed = copy.deepcopy(command())
                resumed["eventId"] = str(uuid.uuid4())
                resumed["eventType"] = "DIAGNOSIS_RESUMED"
                resumed["payload"]["round"] = 1
                resumed["inputRevision"] = 2                       # 계약: 제출마다 +1
                resumed["executionId"] = str(uuid.uuid4())
                resumed["payload"]["requestId"] = "1"
                resumed["payload"]["requestKey"] = first["payload"]["requestKey"]
                resumed["payload"]["checkpoint"] = first["payload"]["checkpoint"]
                extra = {**copy.deepcopy(resumed["payload"]["attachments"][0]), "id": "2"}
                resumed["payload"]["attachments"] += [extra]
                resumed["payload"]["attachmentIds"] = ["1", "2"]
                resumed["payload"]["addedAttachmentIds"] = ["2"]
                second = await main.handle(resumed, producer, journal, graph=app)
                return first, second, producer
            finally:
                journal.close()

    first, second, producer = asyncio.run(run())
    assert first["eventType"] == "PHOTO_REQUESTED" and first["payload"]["requestKey"]
    assert second["eventType"] == "DIAGNOSIS_COMPLETED"
    assert [e["eventType"] for e in producer.events].count("DIAGNOSIS_COMPLETED") == 1


def test_finished_job_leaves_no_checkpoint_but_paused_one_remains(fixture_tools, tmp_path):
    """끝난 작업의 상태는 지우고, 기다리는 작업의 상태는 남긴다."""
    from worker import main, tools
    from worker.journal import Journal

    async def run(found):
        tools.call_pcb_mcp.return_value = {**fixture_tools, "is_defective": found,
                                           "defects": [{"type": "short", "confidence": .8}] if found else []}
        async with AsyncSqliteSaver.from_conn_string(":memory:") as saver:
            app = graph.build_graph(saver)
            journal = Journal(tmp_path / f"state-{found}.db")
            try:
                cmd = command()
                result = await main.handle(cmd, Producer(), journal, graph=app)
                config = {"configurable": {"thread_id": str(cmd["taskId"])}}
                return result["eventType"], (await app.aget_state(config)).values
            finally:
                journal.close()

    done_type, done_state = asyncio.run(run(True))
    assert done_type == "DIAGNOSIS_COMPLETED" and not done_state, "끝난 작업은 상태가 남지 않는다"

    paused_type, paused_state = asyncio.run(run(False))
    assert paused_type == "PHOTO_REQUESTED" and paused_state, "기다리는 작업은 상태가 남아야 재개된다"
