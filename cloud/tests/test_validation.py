import pytest
from pydantic import ValidationError

from katago_cloud.main import AnalyzeRequest


def test_default_request_is_19x19_chinese():
    request = AnalyzeRequest()
    assert request.board_x_size == 19
    assert request.rules == "chinese"
    assert request.komi == 7.5


def test_complete_history_and_setup_stones_are_preserved():
    request = AnalyzeRequest(
        board_x_size=19,
        initial_stones=[("B", "D4"), ("W", "Q16")],
        moves=[("B", "C3"), ("W", "pass")],
    )
    assert request.initial_stones == [("B", "D4"), ("W", "Q16")]
    assert request.moves[-1] == ("W", "pass")


@pytest.mark.parametrize("coord", ["I4", "A0", "ZZ99", "not-a-move"])
def test_invalid_move_coordinates_rejected(coord):
    with pytest.raises(ValidationError):
        AnalyzeRequest(moves=[("B", coord)])


def test_board_size_is_bounded():
    with pytest.raises(ValidationError):
        AnalyzeRequest(board_x_size=26)


def test_unknown_fields_are_rejected():
    with pytest.raises(ValidationError):
        AnalyzeRequest(pretend_cloud=True)
