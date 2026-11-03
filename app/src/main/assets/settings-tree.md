# settings-tree.json

The settings screens, the groups on them, and the values that are saved. A reader can add a section, a group, or a setting from the sections below. Each section says which keys are required the first time they appear.

A display string is the en-US sentence, not an Android resource name. That is `title`, `label`, `description`, each `choiceTitles` value, and summary `text`, `plural`, and `titles` values. `smtpNoAuth` is titled "On sends with no SMTP login". Its choices are "No SMTP login" and "SMTP login is required".

The Android resource name for a sentence stays the name that sentence already has. The same sentence stays one resource until the English texts differ. A sentence that changes keeps its name when it is the only user of that name. A shared sentence that changes in one place gets a new name, and an `<locale>.override.json` entry stays on the old name. The override is the manual translation. It is not replaced. Its text is what ships.

Changing an English sentence updates `app/src/main/res/values/strings.xml` and every `app/src/main/i18n/<locale>.json` `source`. Grok, through the xAI API and `XAI_API_KEY`, writes the new machine `text`. No other translation service is called. When Grok cannot run, the new English is still the `source`, the previous machine `text` stays, and the build finishes. A later build retries those strings. Strings that are not display strings of this tree stay on the other rule: the change updates every locale file in the same change.

## Root

The root is an object. It requires `sections` and nothing else. `sections` is the list of settings sections, in the order the Settings screen shows them.

Account settings and UI settings are JSON documents. One account document is one JSON object. The UI document is one JSON object. Keys are flat: `imapHost`, not `server.inbound.url`. A key=value blob is not JSON and is not converted. A syntax error keeps the stored text and shows the error.

## Section

A section is an object. Required: `section`, `title`, and `children`. Optional: `summary`.

`section` is `account` or `ui`. There is one of each, and a section is not nested. `account` is saved on the account. `ui` is saved in the UI record. The section picks the record. A leaf does not name a record. `title` is the section's en-US heading. `children` is an array of groups, editors, and leaves.

## Group

A group is an object. Required: `group`, `title`, and `children`. `group` is the stable id. `title` is the heading text.

Optional: `label` (how a group matches the extra-control headers; it defaults to `title`), `header` (`label` or `arrow`; default `label`), `summary`, `description`, `visibleWhen`, `editor`, `opens`, and `introWhen`. `children` may contain groups, editors, and leaves. `description` stays unused. The screen does not draw `label` as its own text. `summary` is the small line under the heading while the group is collapsed. `introWhen` is the line above the children while the group is open.

A group with one visible leaf and no footer controls is drawn as that single visible leaf. A group whose drawn children are empty is not drawn. Account, Server, Sending, Logging, Layout, Address book, and Mailboxes stay headers. Account opens the server editor. Server, Sending, Logging, Layout, and Address book add a footer under their children.

## Editor

An editor is an object with no `type` and no `setting`. Required: `editor` and `opens` (`screen`, `popup`, or `inline`). Optional: `title`, `label`, `summary`, and `children`.

`accountList` opens on a screen. A group may also name `editor` (the server group). `rules`, `toolbar`, `paneLayout`, and `swipe` are leaf types with their own screens, not a second editor id on the leaf.

## Leaf

A leaf is an object. Required: `setting`, `type`, and `title`. `setting` is the field name and the key in the JSON document. It is unique in the whole file. The section that holds the leaf picks its record.

`type` is the control. The screen chooses the control from `type`. There is no second map from the field name to a control.

`type` is one of: `bool`, `either`, `number`, `choice`, `text`, `password`, `folder`, `mailbox`, `checkServer`, `clientCert`, `pinerc`, `settingsFile`, `rules`, `toolbar`, `paneLayout`, `swipe`.

`bool` is a switch. `either` is a bool drawn as two side labels with the thumb between them. A `choice` with three or more values is a dropdown. A two-value `choice` that is not `either` stays a dropdown.

`number` is one type. `min` and `max` are enforced limits. Whole numbers in the document are 64-bit integers. A fraction, an exponent, or a number that does not fit in a signed 64-bit integer fails the parse.

`password` stores nothing in the document. The field name is the keystore slot.

`folder` is an object: mailbox, friendly name, container flag, delimiter. A blank friendly name displays the mailbox, or the mailbox plus the delimiter. Favorites are `many` `folder`. Special-use settings stay `mailbox`, a mailbox name, not a `folder`.

`checkServer` is a row with no stored value. `clientCert` stores the keystore alias. The certificate file and its password are not stored. `pinerc` picks a Pine file, previews it, and writes settings. The file is not kept. `settingsFile` imports or exports this app's JSON. `rules`, `toolbar`, `paneLayout`, and `swipe` keep their own screens. One swipe is one object, not a list.

`many` is a modifier on `bool`, `number`, `text`, `folder`, or `mailbox`. Then `order` is required. `order` is `user`, `sorted`, or `increasing`. There is no stored ordered flag. `user` saves the order the user arranged. `sorted` has no up and down; the clean form is sorted with duplicates dropped. `increasing` has no up and down; the clean form is numbers from low to high, and the first number is the first wait.

Optional: `hidden`, `summary`, `label`, `description`, `choices`, `choiceTitles`, `min`, `max`, `minItems`, `maxItems`, `itemMin`, `itemMax`, `order`, `many`, `server`, `derived`, `pinerc` with required `pinercFormat`, `visibleWhen`, `enabledWhen`. `hidden` true is saved and is not drawn. `visibleWhen` still hides a leaf that is not hidden. An `choice` that sets `choices` needs a `choiceTitles` entry for each choice. `choiceTitles` values are the en-US choice labels. `choiceTitle` is a summary argument that uses those labels.

Keys the parser accepts and does not apply: `titleWhen`, `noteWhen`, `format`, `nullable`, `titleArgs`. `titleWhen` on `imapHost` does not switch the row title.

## Condition

A condition is `all`, `any`, `not`, `sdkAtLeast`, or `setting` with one of `equals`, `notEquals`, `in`, `empty`. `all`, `any`, and `not` contain conditions. `not` wraps one condition. `empty: false` is still a parse error. Hiding a row when a field is filled is `not` around `empty`. `visibleWhen` hides the node. `enabledWhen` leaves it visible and disables it.

## Summary

A summary is an ordered list of cases on a section, group, editor, or leaf. The first case whose `when` matches is shown. A case with no `when` matches. No matching case means no summary line. The last case has no `when`.

One case is `text` plus optional `args`, or `plural` plus `count`, or `value`. `text`, `plural`, and `titles` values are en-US sentences. An argument is `setting` or `choiceTitle`, with optional `field` (`defaultView` uses `key` and `newestFirst`; a swipe uses `action`, `moveMailbox`, and `flag`). `titles` maps that raw value to a display string. `count` is the size of a list setting, or `$accounts` for the number of accounts. `countSet` is how many of the named settings are non-blank. `format` `rulesWho` is the sending-rules phrase.

## Documents

Each account document and the UI document are one JSON object. The app parses once at load and writes once at save. A key absent from every document loads its default. That is not a warning. A renamed key does not keep the old key's value. The old key is an unknown key.

A key the tree places in one record and that is missing there is looked for in the other record. One distinct value moves on save into the record the tree names. A UI value is copied into every account. Account values become the one UI value only when every account that has the key agrees. An account that lacks the key does not count. The old copies are removed in that same save. Load does not write that move.

Every other policy problem found while saving is one dialog. The lines are the problems. The choices are Keep editing, Save anyway, and Clean. Save anyway stores each warned value as the user left it. Clean applies the action named on every line.
