# Group stories

Open **Group stories** from the group chat menu. Current members can view photos and MP4 stories, including a separate list of pinned stories. Android publishes for 24 hours. The server accepts 6-, 12-, 24-, and 48-hour lifetimes, up to 20 active stories and up to 20 pinned stories per group.

Posting defaults to owners and administrators who can change group info. Administrators who can manage group send permissions can enable posting for ordinary members. The group's message and media restrictions still apply. Keeping an item pinned requires pin permission; authors can delete their own stories, and admins with delete permission can remove others' stories. Non-members cannot retrieve a group's stories or gain access to media through them.

API: `/api/groups/{id}/stories`, `/stories/can-post`, `/stories/delete`, and `PUT /api/groups/{id}/story-settings` with `{"members_can_post": true}`. Story creation deduplicates retries by the group's ID, author and `random_id`.

## Видимость историй

`GET /api/stories/all` отдаёт активные истории всех каналов и групп, доступных аккаунту
(`{"count": n, "peers": [{"scope": "channel"|"group", "id": N, "stories": [...]}]}`).
Это ответ на `stories.getAllStories` — без него клиент не знал ни одного пира с историями,
поэтому кольца историй не появлялись и чужую историю нельзя было увидеть, даже открыв
канал. Просроченные истории в список не попадают, закреплённые — остаются.

Клиент (`WaysGramStories`) теперь считает историевым пиром и группу: путь строится по
диапазону id (`/api/groups/<id>/stories` ниже `CHANNEL_OFFSET`, `/api/channels/<id>/stories`
выше). Карточка группы отдаёт `stories_active`/`stories_pinned_available`, а `fullChat`
кладёт их в `TL_channelFull` — поэтому кольцо истории есть и в группе.
