import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles } from '../styles/components';

@customElement('versola-pagination')
export class VersolaPagination extends LitElement {
  @property({ type: Number }) total = 0;
  @property({ type: Number }) offset = 0;
  @property({ type: Number }) limit = 10;
  @property({ type: Boolean }) estimatedTotal = false;

  static styles = [
    theme,
    buttonStyles,
    css`
      :host {
        display: block;
      }

      .pagination {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 1rem 0;
        font-size: 0.875rem;
      }

      .pagination-info {
        color: var(--text-secondary);
      }

      .pagination-controls {
        display: flex;
        gap: 0.5rem;
      }

      .page-number {
        min-width: 36px;
        height: 36px;
        display: flex;
        align-items: center;
        justify-content: center;
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-sm);
        cursor: pointer;
        transition: all var(--transition-fast);
        color: var(--text-secondary);
      }

      .page-number:hover:not(.active) {
        border-color: var(--accent);
        color: var(--accent);
      }

      .page-number.active {
        background: var(--accent);
        border-color: var(--accent);
        color: white;
      }
    `,
  ];

  get currentPage(): number {
    return Math.floor(this.offset / this.limit) + 1;
  }

  get totalPages(): number {
    return Math.max(1, Math.ceil(this.total / this.limit));
  }

  private handlePageChange(page: number) {
    const newOffset = (page - 1) * this.limit;
    this.dispatchEvent(new CustomEvent('page-change', {
      detail: { offset: newOffset, limit: this.limit },
      bubbles: true,
      composed: true,
    }));
  }

  private renderPageNumbers() {
    const pages = [];
    const maxVisible = 7;
    
    if (this.totalPages <= maxVisible) {
      for (let i = 1; i <= this.totalPages; i++) {
        pages.push(i);
      }
    } else {
      const current = this.currentPage;
      const total = this.totalPages;
      
      if (current <= 4) {
        for (let i = 1; i <= 5; i++) pages.push(i);
        pages.push(-1); // ellipsis
        pages.push(total);
      } else if (current >= total - 3) {
        pages.push(1);
        pages.push(-1);
        for (let i = total - 4; i <= total; i++) pages.push(i);
      } else {
        pages.push(1);
        pages.push(-1);
        for (let i = current - 1; i <= current + 1; i++) pages.push(i);
        pages.push(-1);
        pages.push(total);
      }
    }

    return pages.map(page => 
      page === -1
        ? html`<span class="page-number" style="cursor: default; border: none;">...</span>`
        : html`
            <div
              class="page-number ${page === this.currentPage ? 'active' : ''}"
              @click=${() => this.handlePageChange(page)}
            >
              ${page}
            </div>
          `
    );
  }

  render() {
    const start = this.offset + 1;
    const end = Math.min(this.offset + this.limit, this.total);
    const totalLabel = this.estimatedTotal ? `${Math.max(end, this.total - 1)}+` : this.total;

    return html`
      <div class="pagination">
        <div class="pagination-info">
          Showing ${start}-${end} of ${totalLabel}
        </div>
        
        <div class="pagination-controls">
          <button
            class="btn btn-secondary btn-sm"
            ?disabled=${this.currentPage === 1}
            @click=${() => this.handlePageChange(this.currentPage - 1)}
          >
            Previous
          </button>
          
          ${this.renderPageNumbers()}
          
          <button
            class="btn btn-secondary btn-sm"
            ?disabled=${this.currentPage === this.totalPages}
            @click=${() => this.handlePageChange(this.currentPage + 1)}
          >
            Next
          </button>
        </div>
      </div>
    `;
  }
}

