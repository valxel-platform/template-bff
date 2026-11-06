# Contributing

1. Branch from `main` (`feature/<FEATURE-ID>` when the Vrixel SDLC opens it for you).
2. Keep pull requests small and focused; one concern each.
3. Write commits in the imperative ("Add consent lookup"); reference the feature or work item.
4. Run the checks locally before pushing (see the README's "Develop" section).
5. A pull request merges when its required checks pass and, on repos the Vrixel SDLC delivers to,
   once the pipeline's gates have approved it (status `vrixel/gates`). People approve in the
   gates, not as GitHub reviews. The default branch accepts pull requests only.

Changes to event types and tables follow expand, then contract: a new event version is a new type
(`...v2`) handled next to the old one until producers have moved; a new column is added before it
is used and dropped in a later release. Keep every handler able to replay the topic from the start.
