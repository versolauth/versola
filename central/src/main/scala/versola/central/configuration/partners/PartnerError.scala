package versola.central.configuration.partners

/** What a partner operation refuses, as a value: the controllers map each to a status. */
enum PartnerError:
  /** No partner, approval or client of that id (404). */
  case NotFound(what: String)

  /** The request cannot be taken as it is (400, `text/plain`, like a client's registration rules). */
  case Invalid(reason: String)

  /** The request conflicts with what exists: an id taken, a request already decided (409). */
  case Conflict(reason: String)

  def message: String = this match
    case NotFound(what) => s"$what does not exist"
    case Invalid(reason) => reason
    case Conflict(reason) => reason
