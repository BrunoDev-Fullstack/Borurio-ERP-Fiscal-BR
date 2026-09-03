package br.com.borurio.core.mvc.api;

import java.util.List;

public class PageResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    public PageResponse() {}

    private PageResponse(List<T> content, int page, int size, long totalElements) {
        this.content       = content;
        this.page          = page;
        this.size          = size;
        this.totalElements = totalElements;
        this.totalPages    = size == 0 ? 1 : (int) Math.ceil((double) totalElements / size);
        this.first         = page == 0;
        this.last          = page >= this.totalPages - 1;
    }

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        return new PageResponse<>(content, page, size, totalElements);
    }

    public List<T> getContent()         { return content; }
    public int getPage()                { return page; }
    public int getSize()                { return size; }
    public long getTotalElements()      { return totalElements; }
    public int getTotalPages()          { return totalPages; }
    public boolean isFirst()            { return first; }
    public boolean isLast()             { return last; }
}
