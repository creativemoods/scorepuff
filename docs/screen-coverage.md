# Screen coverage

Support depends on accessibility components, not tab names. A synthetic mock exercises a parser but does not prove that a streaming app exposes that structure. Current recognition follows tested English layouts.

| App / surface | Supported structure | Remaining limits |
| --- | --- | --- |
| Netflix poster rows and search results | Standard Pinot artwork, title and catalog ID; game entities excluded | Other card variants and app updates |
| Netflix Continue Watching | Paired Play/details labels and separate artwork bounds | Other control labels/locales; ID usually absent |
| Netflix search landing | Horizontal artwork treatment with separate title and image | Other recommendation components |
| Netflix My List | Native GridView image/title/Play relationships | Cards lack a verified catalog ID |
| Netflix details | Title/year/type metadata with recognized Play/Resume or episode controls | Season/episode variants; retain the displayed year |
| Prime Home / movie browsing | Clickable artwork containers with a matching title label | Other tabs, hero layouts and languages |
| Prime series details | Branded heading, Releases/Seasons metadata and Resume episode control | Other playback-control forms and seasons |
| Prime movie details | Text/image title, Release/Duration metadata and Resume control | Other details layouts and playback-control forms |
| Sharing | Netflix title URLs and Prime Android GTI detail URLs | Short links and unverified URL forms rejected |
| Top 10 / other grids / wide cards | Synthetic regression coverage for known geometry | Requires live component verification |
| Playback / other apps / lock / landscape | Conservative overlay hide guards | Broader OEM, PiP and portrait-player coverage |

Bottom navigation and system insets clip badge placement. Very narrow/clipped artwork is skipped. Empty recognized-title sets clear the overlay. Failed overlay attachment is retried without terminating the rating collector.

## Metadata regressions

Focused public examples cover title ambiguity, dated details and movie runtimes. Payback (1999) tests a feature/short namesake; The Wolf of Wall Street tests a unique dated movie with different Prime/OMDb durations. A displayed series year is never replaced with an assumed original year merely to obtain a match.

## Fixture hygiene

`app/src/test/resources/fixtures/` contains only reduced parser inputs: bounds, widget relationships/classes, relevant resource IDs and required labels. Browse, saved-list and Continue Watching inputs use invented titles/IDs/progress. Focused film/year/runtime fixtures and catalog-link tests retain public metadata needed to reproduce specific matching cases.

Do not commit raw phone dumps, screenshots, account/profile labels, notifications, clipboard content, device identifiers or databases. Keep new raw captures under ignored `local-captures/`, then reduce and anonymize them before adding a fixture. Preserve the geometry/label relationships under test, update assertions and run the regression suite. The debug playground uses synthetic artwork/scores and these same fixtures.

## Next verification

- Other details control labels, season selection and episode identity.
- Live Top 10, downloads and additional discovery layouts.
- Other interface languages and Kids layouts.
- App switching, sharing, keyboard, playback, rotation and lock/unlock transitions on more devices.
