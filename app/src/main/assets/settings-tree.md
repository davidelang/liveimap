# settings-tree.json

The settings screens, the groups on them, and the values that are saved. A reader can add a page, a group, or a setting from the sections below. Each section says which keys are required the first time they appear.

A display string is the en-US sentence, not an Android resource name. That is `title`, `label`, `description`, each `choiceTitles` value, and summary `text`, `plural`, and `titles` values. `smtpNoAuth` is titled "On sends with no SMTP login". Its choices are "No SMTP login" and "SMTP login is required".

The Android resource name for a sentence stays the name that sentence already has. The same sentence stays one resource until the English texts differ. A sentence that changes keeps its name when it is the only user of that name. A shared sentence that changes in one place gets a new name, and an `<locale>.override.json` entry stays on the old name. The override is the manual translation. It is not replaced. Its text is what ships.

Changing an English sentence updates `app/src/main/res/values/strings.xml` and every `app/src/main/i18n/<locale>.json` `source`. Grok, through the xAI API and `XAI_API_KEY`, writes the new machine `text`. No other translation service is called. When Grok cannot run, the new English is still the `source`, the previous machine `text` stays, and the build finishes. A later build retries those strings. Strings that are not display strings of this tree stay on the other rule: the change updates every locale file in the same change.

## Root

The root is either an object or a bare array of pages.

An object requires `pages` and may include `stored`. `pages` is the list of settings screens. `stored` is leaves that are saved and are not drawn. A bare array is only the pages.

## Page

A page is an object. Required: `page`, `title`, and `children`. Optional: `summary`.

`page` is `top`, `ui`, or `account`. `top` is the first settings screen, `ui` is the app-wide screen, and `account` is the open account. `title` is the screen's en-US heading. `children` is an array of groups, editors, and leaves.

A `ui` leaf is not on the account page. An `account` leaf is not on the ui page.

## Group

A group is an object. Required: `group`, `title`, and `children`. `group` is the stable id. `title` is the heading text.

Optional: `label` (the heading when it differs from `title`; it defaults to `title`), `header` (`label` or `arrow`; default `label`), `summary`, `description`, `visibleWhen`, `editor`, `opens`, and `introWhen`. `children` may contain groups, editors, and leaves. `description` is en-US text under the heading. `introWhen` is a summary, shown above the children, for the first case that matches.

A group with one visible leaf and no footer controls is drawn as that single visible leaf. Account, Server, Sending, Logging, Layout, Address book, and Mailboxes stay headers. Account opens the server editor. Server, Sending, Logging, Layout, and Address book add a footer under their children.

## Editor

An editor is an object. Required: `editor` and `opens` (`screen`, `popup`, or `inline`). Optional: `title`, `label`, `summary`, and either `settings` or `children`.

Editor ids and the `opens` value each one allows: `hiddenOptions` screen, `swipes` popup, `clientCert` inline, `server` inline, `rules` screen, `folderLists` screen, `addressBooks` screen, `paneLayout` screen, `compression` inline, `deletePolicy` inline, `moveMethod` inline, `toolbars` screen, `accountList` screen, `pinerc` screen.

## Leaf

A leaf is an object. Required: `setting`, `type`, `scope`, `kind`, `default`, and `title`. A leaf in `stored` may omit `title`. `setting` is unique in the whole file. `scope` is `ui` or `account`.

`type` is the stored value: `Bool`, `Int`, `Long`, `String`, `Enum`, `StringList`, `IntList`, `Encoded`. `kind` is the control: `bool` is a switch, `choice` is a dropdown with however many choices it has, `number` is a number, `mailbox` picks a mailbox, `subscreen` opens a screen, and any other non-empty kind is a text row. Two enum choices stay a dropdown.

Optional: `summary`, `choices`, `choiceTitles`, `min`, `max`, `minItems`, `maxItems`, `itemMin`, `itemMax`, `order` (`nondecreasing`, `increasing`, `any`), `server`, `derived`, `pinerc` with required `pinercFormat`, `visibleWhen`, `enabledWhen`, `editor`, `opens`. `min` and `max` belong only on `Int` and `Long`. List bounds belong only on `StringList`, `IntList`, and `Encoded`. An `Enum` that sets `choices` needs a `choiceTitles` entry for each choice. `choiceTitles` values are the en-US choice labels.

Keys the parser accepts and does not apply: `titleWhen`, `noteWhen`, `format`, `label`, `nullable`, `titleArgs`. `titleWhen` on `imapHost` does not switch the row title.

## Condition

A condition is `all`, `any`, `sdkAtLeast`, or `setting` with one of `equals`, `notEquals`, `in`, `empty`. `all` and `any` contain conditions. `visibleWhen` hides the node. `enabledWhen` leaves it visible and disables it.

## Summary

A summary is an ordered list of cases on a page, group, editor, or leaf. The first case whose `when` matches is shown. A case with no `when` matches. No matching case means no summary line. The last case has no `when`.

One case is `text` plus optional `args`, or `plural` plus `count`, or `value`. `text`, `plural`, and `titles` values are en-US sentences. An argument is `setting` or `choiceTitle`, with optional `field` (`defaultView` uses `key` and `newestFirst`; a swipe uses `action`, `moveMailbox`, and `flag`). `titles` maps that raw value to a display string. `count` is the size of a list setting, or `$accounts` for the number of accounts. `countSet` is how many of the named settings are non-blank. `format` `rulesWho` is the sending-rules phrase.
