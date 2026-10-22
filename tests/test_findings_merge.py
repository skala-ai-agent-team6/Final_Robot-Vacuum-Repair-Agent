"""findings 리듀서: 같은 대상의 결과는 쌓지 않고 최신으로 교체한다."""
from worker.graph import decide, merge_findings


def photo(source, attachment, found=False, error=None, tool="detect"):
    output = {"tool": tool, "error": error} if error else {"is_defective": found}
    return {"source": source, "attachmentId": attachment, "found": found, "output": output}


def test_same_photo_is_not_counted_twice():
    first = [photo("PCB", "1", found=True)]
    assert len(merge_findings(first, [photo("PCB", "1", found=True)])) == 1


def test_retry_success_replaces_earlier_failure():
    failed = [photo("PCB", "1", error="ConnectionError")]
    merged = merge_findings(failed, [photo("PCB", "1", found=True)])
    assert len(merged) == 1 and merged[0]["found"] and not merged[0]["output"].get("error")
    # 이전 실패가 남아 있으면 3회를 다 써도 실패로 끝난다. 교체되면 정상 판정이 나온다.
    assert decide(failed, 3) == "FAIL"
    assert decide(merged, 3) == "COMPLETE"


def test_different_photos_and_sources_are_kept():
    merged = merge_findings([photo("PCB", "1", found=True)],
                            [photo("PCB", "2"), photo("MECH", "1")])
    assert len(merged) == 3


def test_interpretation_tools_are_separated_by_tool_name():
    a = {"source": "INTERPRETATION", "attachmentId": None, "found": False,
         "output": {"tool": "interpret_pcb", "detections": []}}
    b = {"source": "INTERPRETATION", "attachmentId": None, "found": False,
         "output": {"tool": "normalize_observation", "observations": []}}
    assert len(merge_findings([a], [b])) == 2
    assert len(merge_findings([a], [{**a, "found": False}])) == 1


def test_classification_failure_is_cleared_when_it_later_succeeds():
    failed = [photo("CLASSIFICATION", "1", error="TimeoutError", tool="classify_photo")]
    resolved = [{"source": "CLASSIFICATION", "attachmentId": "1", "found": False,
                 "output": {"tool": "classify_photo", "status": "ok"}}]
    merged = merge_findings(failed, resolved)
    assert len(merged) == 1 and not merged[0]["output"].get("error")
