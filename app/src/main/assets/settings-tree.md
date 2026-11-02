# settings-tree.json

What `settings-tree.json` can set. The parser reads these keys.

## Root

A `pages` array, or a bare array of pages, plus an optional `stored` array of leaves.

## Page

`page` (`top`, `ui`, or `account`), `title`, `children`, `summary`.

## Group

Keys that are read: `group`, `title`, `label`, `header` (`label` or `arrow`), `children`, `summary`, `description`, `visibleWhen`, `editor`, `opens`, `introWhen`.

Account is the server editor. Server, Sending, Logging, Layout, and Address book add a footer. A group with a single visible leaf and none of the extra controls named above is shown as that leaf.

## Editor

`editor`, `opens` (`screen`, `popup`, or `inline`), `title`, `label`, `summary`, and either `settings` or `children`.

Editor ids and the `opens` value each one allows: `hiddenOptions` screen, `swipes` popup, `clientCert` inline, `server` inline, `rules` screen, `folderLists` screen, `addressBooks` screen, `paneLayout` screen, `compression` inline, `deletePolicy` inline, `moveMethod` inline, `toolbars` screen, `accountList` screen, `pinerc` screen.

## Leaf

Keys that are read: `setting`, `type` (`Bool`, `Int`, `Long`, `String`, `Enum`, `StringList`, `IntList`, `Encoded`), `scope` (`ui` or `account`), `kind` (`number`, `bool`, `choice`, `mailbox`, `subscreen`; any other non-empty kind is a text row), `default`, `title`, `summary`, `choiceTitles`, `choices`, `min`, `max`, `minItems`, `maxItems`, `itemMin`, `itemMax`, `order` (`nondecreasing`, `increasing`, `any`), `server`, `derived`, `pinerc`, `pinercFormat`, `visibleWhen`, `enabledWhen`, `editor`, `opens`.

A stored leaf is the same and lives in `stored`.

Keys the parser accepts and does not apply: `titleWhen`, `noteWhen`, `format`, `label`, `nullable`, `titleArgs`.

## Condition

`all`, `any`, `sdkAtLeast`, or `setting` with one of `equals`, `notEquals`, `in`, `empty`.

## Summary

The first matching `when`, then `text` with `args`, or `plural` with `count`, or `value`. A value can be `setting`, `choiceTitle`, `titles`, `field`, `count`, `countSet`, or `format` `rulesWho`. `count` of `$accounts` is the account total.
