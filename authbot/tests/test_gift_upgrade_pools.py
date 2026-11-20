"""Official Telegram upgrade pools: names, chances and collection numbers."""

import json
import random
from pathlib import Path

import gift_upgrades
import messenger_api


def test_every_pool_has_all_three_attribute_slots():
    pools, _ = gift_upgrades._load()
    assert len(pools) > 100
    for key, pool in pools.items():
        assert pool["title"], key
        for slot in ("models", "patterns", "backdrops"):
            assert pool[slot], (key, slot)
            for entry in pool[slot]:
                assert entry[0].strip(), (key, slot, entry)
        # Auction-only gifts ship without a catalog upgrade price.
        assert pool["upgrade_stars"] >= 0


def test_published_chances_never_exceed_a_full_thousand_permille():
    pools, _ = gift_upgrades._load()
    for key, pool in pools.items():
        for slot in ("models", "patterns", "backdrops"):
            published = sum(int(entry[1]) for entry in pool[slot] if len(entry) == 2)
            assert published <= gift_upgrades.PERMILLE_TOTAL, (key, slot, published)


def test_slot_weights_follow_the_published_permille_values():
    entries = [["Rare model", 1], ["Common model", 9], ["Legendary model", 0, "legendary"]]
    weights = gift_upgrades._slot_weights(entries)
    assert weights[0] == 1
    assert weights[1] == 9
    # 990 ‰ that no published chance covers go to the class-only attribute.
    assert weights[2] == 990


def test_rolls_match_the_declared_distribution():
    pool = {
        "models": [["Always", 999], ["Almost never", 1]],
        "patterns": [["Only", 1000]],
        "backdrops": [["Only", 1000, 1, 2, 3, 4]],
    }
    random.seed(7)
    hits = 0
    runs = 4000
    for _ in range(runs):
        if gift_upgrades.roll_traits(pool, 0)["model"]["name"] == "Almost never":
            hits += 1
    # 1 ‰ of 4000 rolls is about 4 hits; allow a wide band for randomness.
    assert 0 <= hits <= 25


def test_roll_traits_keeps_the_original_names_and_colours():
    key, pool = gift_upgrades.pool_for_gift(None, "Plush Pepe")
    assert key and pool
    traits = gift_upgrades.roll_traits(pool, 1700000000)
    model_names = {entry[0] for entry in pool["models"]}
    pattern_names = {entry[0] for entry in pool["patterns"]}
    backdrop_names = {entry[0] for entry in pool["backdrops"]}
    assert traits["model"]["name"] in model_names
    assert traits["pattern"]["name"] in pattern_names
    assert traits["backdrop"]["name"] in backdrop_names
    assert traits["backdrop"]["center_color"] > 0
    assert traits["backdrop"]["edge_color"] > 0
    assert gift_upgrades.is_current_traits(traits)


def test_unknown_gift_falls_back_to_a_neutral_pool():
    key, pool = gift_upgrades.pool_for_gift("g1", "Подарок Telegram")
    assert key is None and pool is None
    traits = gift_upgrades.roll_traits(gift_upgrades.FALLBACK_POOL, 0)
    assert gift_upgrades.is_current_traits(traits)


def test_reveal_pool_always_contains_the_rolled_attributes():
    _, pool = gift_upgrades.pool_for_gift(None, "Plush Pepe")
    traits = gift_upgrades.roll_traits(pool, 0)
    reveal = gift_upgrades.reveal_pool(pool, traits)
    models = {entry[0] for entry in reveal["models"]}
    patterns = {entry[0] for entry in reveal["patterns"]}
    backdrops = {entry[0] for entry in reveal["backdrops"]}
    assert traits["model"]["name"] in models
    assert traits["pattern"]["name"] in patterns
    assert traits["backdrop"]["name"] in backdrops
    # The reels stay short enough to animate.
    assert 1 < len(reveal["backdrops"]) <= 40


def test_pools_file_is_valid_json():
    data = json.loads(Path(gift_upgrades.POOLS_FILE).read_text(encoding="utf-8"))
    assert data["version"] == gift_upgrades.TRAITS_VERSION
    assert data["gifts"]


def test_repair_does_not_replace_an_existing_model_with_a_preview():
    traits = {"model": {"name": "Existing", "document_id": 123},
              "pattern": {"name": "Old pattern"}}
    rows = {"model": [{"name": "Sample", "document_id": 456, "permille": 1, "position": 0}],
            "pattern": [{"name": "Pattern sample", "document_id": 789, "permille": 1, "position": 0}]}
    assert not messenger_api._enrich_traits_documents(traits, {}, rows)
    assert traits["model"] == {"name": "Existing", "document_id": 123}
    assert traits["pattern"] == {"name": "Old pattern"}


def test_repair_does_not_match_single_sample_by_chance_or_position():
    traits = {"model": {"name": "Original", "permille": 1, "position": 0, "pool_size": 1}}
    rows = {"model": [{"name": "Different", "document_id": 456, "permille": 1, "position": 0}]}
    assert not messenger_api._enrich_traits_documents(traits, {}, rows)
    assert traits["model"]["name"] == "Original"
    assert "document_id" not in traits["model"]


def test_rare_backdrop_and_other_slots_use_weighted_draws(monkeypatch):
    # Exercise exact random boundaries, not a flaky statistical assertion.
    import gift_upgrades as upgrades
    pool = {
        "models": [["Rare", 1], ["Common", 9]],
        "patterns": [["Rare", 2], ["Common", 8]],
        "backdrops": [["Rare", 1, 1, 2, 3, 4], ["Common", 9, 1, 2, 3, 4]],
    }
    monkeypatch.setattr(upgrades.secrets, "randbelow", lambda n: 0)
    rare = upgrades.roll_traits(pool, 0)
    assert [rare[slot]["name"] for slot in ("model", "pattern", "backdrop")] == ["Rare"] * 3
    assert [rare[slot]["permille"] for slot in ("model", "pattern", "backdrop")] == [100, 200, 100]
    monkeypatch.setattr(upgrades.secrets, "randbelow", lambda n: n - 1)
    common = upgrades.roll_traits(pool, 0)
    assert [common[slot]["name"] for slot in ("model", "pattern", "backdrop")] == ["Common"] * 3
    assert upgrades._slot_weights(pool["backdrops"], colored=True) == [1, 9]


def test_reupgrade_is_independent_and_displays_effective_chances(monkeypatch):
    pool = {"models": [["Rare", 1], ["Common", 999]],
            "patterns": [["One", 1000]],
            "backdrops": [["One", 1000, 1, 2, 3, 4]]}
    monkeypatch.setattr(gift_upgrades.secrets, "randbelow", lambda n: 0)
    first = gift_upgrades.roll_traits(pool, 1)
    again = gift_upgrades.roll_traits(pool, 2, previous=first)
    assert first["model"]["name"] == again["model"]["name"] == "Rare"
    assert again["model"]["permille"] == 1


def test_class_only_and_partial_pool_show_effective_chances(monkeypatch):
    pool = {"models": [["Known", 10], ["Class only", 0, "rare"]],
            "patterns": [["Only", 4]],
            "backdrops": [["Only", 5, 1, 2, 3, 4]]}
    monkeypatch.setattr(gift_upgrades.secrets, "randbelow", lambda n: n - 1)
    traits = gift_upgrades.roll_traits(pool, 0)
    assert traits["model"]["permille"] == 990
    assert traits["pattern"]["permille"] == 1000
    assert traits["backdrop"]["permille"] == 1000


def test_known_shield_model_never_matches_different_partial_rows_by_chance():
    traits = {"model": {"name": "Shield of Sun", "permille": 15, "position": 0, "pool_size": 2}}
    rows = {"model": [
        {"name": "Other", "document_id": 123, "permille": 15, "position": 0},
        {"name": "Another", "document_id": 456, "permille": 10, "position": 1},
    ]}
    assert not messenger_api._enrich_traits_documents(
        traits, {}, rows, gift_id="5868455043362980631"
    )
    assert "document_id" not in traits["model"]
    rows["model"].append({"name": "Shield of Sun", "document_id": 789, "permille": 15, "position": 2})
    assert messenger_api._enrich_traits_documents(
        traits, {}, rows, gift_id="5868455043362980631"
    )
    assert traits["model"]["document_id"] == 789
