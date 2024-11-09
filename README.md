# QuPath Project Initializer

Tools to initialize a [QuPath project](https://qupath.github.io/) from input files

## Development

### Github

Set these repository variables:

- `DOCKERHUB_REPOSITORY` eg `dchaley`
  - If you set this, you need to set these further variables:
    - `_DOCKERHUB_USERNAME_SECRET_NAME` eg `dockerhub-username/versions/1`
    - `_DOCKERHUB_PASSWORD_SECRET_NAME` eg `dockerhub-password/versions/1`

  - And you need the corresponding secrets in the GCP project.

- `GCP_ARTIFACT_REPOSITORY` eg `my-repository`
- `GCP_PROJECT_ID` eg `my-gcp-project-4321`
- `GCP_REGION` eg `us-central1`

The full container address will be constructed with the region, project id, and repository name.
